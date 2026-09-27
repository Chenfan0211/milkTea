package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.marketing.dto.UserCouponView;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 券适用范围的展示口径回归。
 *
 * <p>背景（业务漏洞）：后端 UserCouponView 原先不下发 applicableStoreIds /
 * applicableProductIds，前端恒拿空数组，导致「券适用门店」页永远展示全部门店、
 * 下单时也不做适用范围校验。限定门店/商品的券会被错误放行。
 */
class CouponViewScopeTest {

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final CouponService service = new CouponService(couponMapper, userCouponMapper);

    private Coupon coupon(Long id, String storeIds, String productIds) {
        Coupon coupon = new Coupon();
        coupon.setId(id);
        coupon.setCode("coupon-" + id);
        coupon.setName("限定券" + id);
        coupon.setType("voucher");
        coupon.setAmount(300L);
        coupon.setThreshold(2000L);
        coupon.setStatus("enabled");
        coupon.setValidityType("DAYS");
        coupon.setValidityDays(30);
        coupon.setApplicableStoreIds(storeIds);
        coupon.setApplicableProductIds(productIds);
        return coupon;
    }

    private UserCoupon userCoupon(Long id, Long couponId) {
        UserCoupon uc = new UserCoupon();
        uc.setId(id);
        uc.setUserId(1L);
        uc.setCouponId(couponId);
        uc.setStatus("UNUSED");
        uc.setReceiveTime(LocalDateTime.now().minusDays(1));
        return uc;
    }

    @Test
    @DisplayName("限定门店的券必须下发数字 subjectId 数组")
    void exposesApplicableStoreIdsAsNumbers() {
        when(userCouponMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(userCoupon(101L, 11L)));
        when(couponMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(coupon(11L, "[101,102]", "[1,2]")));

        List<UserCouponView> result = service.myCoupons(1L, CouponService.UNUSED);

        assertEquals(1, result.size());
        UserCouponView view = result.get(0);
        assertEquals(List.of(101L, 102L), view.getApplicableStoreIds(),
                "适用门店必须以数字 subjectId 下发，供前端按 subjectId 过滤");
        assertEquals(List.of(1L, 2L), view.getApplicableProductIds(),
                "适用商品必须以数字 productId 下发");
    }

    @Test
    @DisplayName("未配置适用范围时必须下发空数组（表示不限制），不得为 null")
    void exposesEmptyListsWhenUnrestricted() {
        when(userCouponMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(userCoupon(102L, 12L)));
        when(couponMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(coupon(12L, null, null)));

        List<UserCouponView> result = service.myCoupons(1L, CouponService.UNUSED);

        UserCouponView view = result.get(0);
        assertTrue(view.getApplicableStoreIds() != null && view.getApplicableStoreIds().isEmpty(),
                "未配置适用门店必须下发空数组，前端据此判定为不限制");
        assertTrue(view.getApplicableProductIds() != null && view.getApplicableProductIds().isEmpty(),
                "未配置适用商品必须下发空数组");
    }
}
