package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.ExchangeOrder;
import com.wuling.marketing.entity.PointsCategory;
import com.wuling.marketing.entity.PointsProduct;
import com.wuling.marketing.entity.PointsRecord;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.ExchangeOrderMapper;
import com.wuling.marketing.mapper.PointsCategoryMapper;
import com.wuling.marketing.mapper.PointsEarningRuleMapper;
import com.wuling.marketing.mapper.PointsProductMapper;
import com.wuling.marketing.mapper.PointsRecordMapper;
import com.wuling.marketing.mapper.PointsSigninMapper;
import com.wuling.marketing.mapper.PointsSigninRuleMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PointsServiceTest {

    private PointsProductMapper pointsProductMapper;
    private PointsRecordMapper pointsRecordMapper;
    private PointsCategoryMapper pointsCategoryMapper;
    private CouponMapper couponMapper;
    private UserCouponMapper userCouponMapper;
    private ExchangeOrderMapper exchangeOrderMapper;
    private AppUserMapper appUserMapper;
    private PointsService service;

    @BeforeEach
    void setUp() {
        pointsProductMapper = mock(PointsProductMapper.class);
        pointsRecordMapper = mock(PointsRecordMapper.class);
        pointsCategoryMapper = mock(PointsCategoryMapper.class);
        couponMapper = mock(CouponMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        exchangeOrderMapper = mock(ExchangeOrderMapper.class);
        appUserMapper = mock(AppUserMapper.class);
        service = new PointsService(
                pointsProductMapper,
                pointsRecordMapper,
                mock(PointsSigninMapper.class),
                mock(PointsSigninRuleMapper.class),
                mock(PointsEarningRuleMapper.class),
                appUserMapper,
                exchangeOrderMapper,
                pointsCategoryMapper,
                couponMapper,
                userCouponMapper);
    }

    @Test
    @DisplayName("商品列表只返回启用分类下的启用商品")
    void listProductsOnlyReturnsProductsInEnabledCategories() {
        PointsCategory pet = category("pet", "宠物公益", 1);
        when(pointsCategoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pet));
        when(pointsProductMapper.selectList(any(Wrapper.class))).thenReturn(List.of(product(1L, "pet", 100L, 5, 0)));

        List<PointsProduct> result = service.listProducts(null);

        assertEquals(1, result.size());
        verify(pointsCategoryMapper).selectList(any(Wrapper.class));
        verify(pointsProductMapper).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("分类停用或不存在时商品列表为空且不再查询商品")
    void listProductsReturnsEmptyForDisabledCategory() {
        when(pointsCategoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertTrue(service.listProducts("pet").isEmpty());
        verify(pointsProductMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("all 或空分类返回全部启用分类下的商品")
    void listProductsAllReturnsAllEnabledProducts() {
        PointsCategory pet = category("pet", "宠物公益专区", 1);
        PointsCategory coupon = category("coupon", "优惠券区", 2);
        List<PointsProduct> products = List.of(
                product(1L, "pet", 100L, 5, 0),
                product(2L, "coupon", 200L, 3, 0));
        when(pointsCategoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pet, coupon));
        when(pointsProductMapper.selectList(any(Wrapper.class))).thenReturn(products);

        assertEquals(products, service.listProducts(null));
        assertEquals(products, service.listProducts(""));
        assertEquals(products, service.listProducts("all"));
        verify(pointsProductMapper, org.mockito.Mockito.times(3)).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("未知分类返回空列表且不查询商品")
    void listProductsReturnsEmptyForUnknownCategory() {
        when(pointsCategoryMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(category("pet", "宠物公益专区", 1)));

        assertTrue(service.listProducts("unknown").isEmpty());
        verify(pointsProductMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("普通商品按数量扣库存、积分并计入限购")
    void normalExchangeUsesQuantity() {
        PointsProduct product = product(11L, "pet", 100L, 10, 5);
        stubExchangeProduct(product, 0L);
        when(pointsProductMapper.deductStock(11L, 2)).thenReturn(1);
        stubPointChange(1000L, -200L, 1);

        ExchangeOrder order = service.exchange(7L, 11L, 2);

        assertEquals(2, order.getQuantity());
        assertEquals(200L, order.getPoints());
        assertEquals(PointsService.PENDING, order.getStatus());
        assertNotNull(order.getPickupCode());
        verify(pointsProductMapper).deductStock(11L, 2);
        verify(appUserMapper).addPoints(7L, -200L);
        verify(exchangeOrderMapper).insert(order);
    }

    @Test
    @DisplayName("限购达到上限时拒绝且不扣库存和积分")
    void exchangeRejectsWhenPurchaseLimitReached() {
        PointsProduct product = product(12L, "pet", 100L, 10, 2);
        stubExchangeProduct(product, 2L);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.exchange(7L, 12L, 1));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        verify(pointsProductMapper, never()).deductStock(anyLong(), any());
        verify(appUserMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    @DisplayName("限购为0时不限制累计兑换数量")
    void zeroPurchaseLimitIsUnlimited() {
        PointsProduct product = product(13L, "pet", 100L, 10, 0);
        stubExchangeProduct(product, 99L);
        when(pointsProductMapper.deductStock(13L, 1)).thenReturn(1);
        stubPointChange(1000L, -100L, 1);

        ExchangeOrder order = service.exchange(7L, 13L, 1);

        assertEquals(1, order.getQuantity());
        verify(exchangeOrderMapper).insert(order);
    }

    @Test
    @DisplayName("优惠券商品强制兑换1张并直接完成")
    void couponExchangeCreatesCompletedOrderAndUserCoupon() {
        PointsProduct product = product(21L, "coupon", 300L, 10, 1);
        product.setCouponId(9L);
        stubExchangeProduct(product, 0L);
        when(couponMapper.selectById(9L)).thenReturn(coupon(9L, "DAYS", 15));
        when(pointsProductMapper.deductStock(21L, 1)).thenReturn(1);
        stubPointChange(1000L, -300L, 1);
        when(couponMapper.deductStock(9L)).thenReturn(1);

        ExchangeOrder order = service.exchange(7L, 21L, 5);

        assertEquals(1, order.getQuantity());
        assertEquals(300L, order.getPoints());
        assertEquals(PointsService.COMPLETED, order.getStatus());
        assertNull(order.getPickupCode());
        ArgumentCaptor<UserCoupon> userCoupon = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(userCoupon.capture());
        assertEquals(PointsService.SOURCE_POINTS_EXCHANGE, userCoupon.getValue().getSource());
        assertEquals("UNUSED", userCoupon.getValue().getStatus());
        assertEquals(9L, userCoupon.getValue().getCouponId());
        verify(couponMapper).deductStock(9L);
    }

    @Test
    @DisplayName("绑定券停用时拒绝且不扣商品库存或积分")
    void couponExchangeRejectsDisabledCouponBeforeStockDeduction() {
        PointsProduct product = product(22L, "coupon", 300L, 10, 0);
        product.setCouponId(10L);
        stubExchangeProduct(product, 0L);
        Coupon disabled = coupon(10L, "DAYS", 15);
        disabled.setStatus("disabled");
        when(couponMapper.selectById(10L)).thenReturn(disabled);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.exchange(7L, 22L, 1));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        verify(pointsProductMapper, never()).deductStock(anyLong(), any());
        verify(appUserMapper, never()).addPoints(anyLong(), anyLong());
    }

    @Test
    @DisplayName("已完成兑换单不可走自提核销")
    void completedCouponOrderCannotBeVerified() {
        ExchangeOrder completed = new ExchangeOrder();
        completed.setId(100L);
        completed.setStatus(PointsService.COMPLETED);
        completed.setPickupCode(null);
        when(exchangeOrderMapper.selectOne(any(Wrapper.class))).thenReturn(completed);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.verifyExchange("CZ123"));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        verify(exchangeOrderMapper, never()).updateById(any(ExchangeOrder.class));
    }

    private void stubExchangeProduct(PointsProduct product, Long usedQuantity) {
        when(pointsProductMapper.selectByIdForUpdate(product.getId())).thenReturn(product);
        when(pointsCategoryMapper.selectOne(any(Wrapper.class))).thenReturn(category(product.getCategory(), "分类", 1));
        when(exchangeOrderMapper.sumSuccessfulQuantity(7L, product.getId())).thenReturn(usedQuantity);
    }

    private void stubPointChange(long currentPoints, long delta, int affected) {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setPoints(currentPoints);
        when(appUserMapper.selectById(7L)).thenReturn(user);
        when(appUserMapper.addPoints(7L, delta)).thenReturn(affected);
        when(pointsRecordMapper.insert(any(PointsRecord.class))).thenReturn(1);
    }

    private PointsProduct product(Long id, String category, long points, int stock, int purchaseLimit) {
        PointsProduct product = new PointsProduct();
        product.setId(id);
        product.setCode("product-" + id);
        product.setName("商品" + id);
        product.setCategory(category);
        product.setPoints(points);
        product.setStock(stock);
        product.setPurchaseLimit(purchaseLimit);
        product.setStatus("enabled");
        return product;
    }

    private PointsCategory category(String code, String name, int enabled) {
        PointsCategory category = new PointsCategory();
        category.setCode(code);
        category.setName(name);
        category.setSort(1);
        category.setEnabled(enabled);
        category.setSystemLocked("coupon".equals(code) ? 1 : 0);
        return category;
    }

    private Coupon coupon(Long id, String validityType, Integer days) {
        Coupon coupon = new Coupon();
        coupon.setId(id);
        coupon.setStatus("enabled");
        coupon.setValidityType(validityType);
        coupon.setValidityDays(days);
        return coupon;
    }
}