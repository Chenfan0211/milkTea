package com.wuling.marketing.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.dto.PointsCategoryView;
import com.wuling.marketing.entity.ExchangeOrder;
import com.wuling.marketing.entity.PointsCategory;
import com.wuling.marketing.entity.PointsProduct;
import com.wuling.marketing.mapper.MemberLevelMapper;
import com.wuling.marketing.service.CouponService;
import com.wuling.marketing.service.GiftCardService;
import com.wuling.marketing.service.PointsService;
import com.wuling.marketing.service.ReferralConfigService;
import com.wuling.marketing.service.StoredValueService;
import com.wuling.security.CurrentUser;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppMarketingControllerPointsExchangeTest {

    private static final Long USER_ID = 7L;

    private PointsService pointsService;
    private AppMarketingController controller;

    @BeforeEach
    void setUp() {
        pointsService = mock(PointsService.class);
        controller = new AppMarketingController(
                mock(CouponService.class),
                mock(StoredValueService.class),
                mock(GiftCardService.class),
                pointsService,
                mock(ReferralConfigService.class),
                mock(AppUserMapper.class),
                mock(MemberLevelMapper.class));
        CurrentUser.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @DisplayName("分类接口只返回小程序需要的 code/name/sort")
    void listsDynamicCategories() {
        PointsCategory category = new PointsCategory();
        category.setCode("pet");
        category.setName("宠物公益专区");
        category.setSort(10);
        when(pointsService.listCategories()).thenReturn(List.of(category));

        Result<List<PointsCategoryView>> result = controller.pointsCategories();

        assertEquals(1, result.getData().size());
        assertEquals("pet", result.getData().get(0).code());
        assertEquals("宠物公益专区", result.getData().get(0).name());
        assertEquals(10, result.getData().get(0).sort());
        verify(pointsService).listCategories();
    }

    @Test
    @DisplayName("商品接口透传分类筛选参数")
    void passesCategoryFilter() {
        List<PointsProduct> products = List.of(new PointsProduct());
        when(pointsService.listProducts("pet")).thenReturn(products);

        Result<List<PointsProduct>> result = controller.pointsProducts("pet");

        assertSame(products, result.getData());
        verify(pointsService).listProducts("pet");
    }

    @Test
    @DisplayName("兑换请求缺省数量按1处理")
    void exchangeDefaultsQuantityToOne() {
        ExchangeOrder order = new ExchangeOrder();
        when(pointsService.exchange(USER_ID, 11L, 1)).thenReturn(order);

        Result<ExchangeOrder> result = controller.exchange(Map.of("productId", 11L));

        assertSame(order, result.getData());
        verify(pointsService).exchange(USER_ID, 11L, 1);
    }

    @Test
    @DisplayName("兑换请求透传普通商品数量")
    void exchangePassesQuantity() {
        ExchangeOrder order = new ExchangeOrder();
        when(pointsService.exchange(USER_ID, 11L, 3)).thenReturn(order);

        Result<ExchangeOrder> result = controller.exchange(Map.of("productId", "11", "quantity", 3L));

        assertSame(order, result.getData());
        verify(pointsService).exchange(USER_ID, 11L, 3);
    }

    @Test
    @DisplayName("兑换数量非法时拒绝且不调用服务")
    void rejectsInvalidQuantity() {
        assertRejected(Map.of("productId", 11L, "quantity", List.of(1L, 2L)));
        assertRejected(Map.of("productId", 11L, "quantity", "many"));
        verify(pointsService, never()).exchange(any(), any(), any());
    }

    private void assertRejected(Map<String, Object> body) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.exchange(body));
        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
    }
}
