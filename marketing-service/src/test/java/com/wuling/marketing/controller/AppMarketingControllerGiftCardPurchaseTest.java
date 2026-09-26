package com.wuling.marketing.controller;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.GiftCardOrder;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppMarketingControllerGiftCardPurchaseTest {

    private static final Long USER_ID = 7L;
    private static final Long DENOMINATION_ID = 11L;

    private GiftCardService giftCardService;
    private AppMarketingController controller;

    @BeforeEach
    void setUp() {
        giftCardService = mock(GiftCardService.class);
        controller = new AppMarketingController(
                mock(CouponService.class),
                mock(StoredValueService.class),
                giftCardService,
                mock(PointsService.class),
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
    @DisplayName("购买请求只接受单个 denominationId")
    void acceptsSingleDenominationId() {
        GiftCardOrder order = new GiftCardOrder();
        when(giftCardService.purchase(USER_ID, DENOMINATION_ID)).thenReturn(order);

        Result<GiftCardOrder> result = controller.purchase(Map.of("denominationId", DENOMINATION_ID));

        assertSame(order, result.getData());
        verify(giftCardService).purchase(USER_ID, DENOMINATION_ID);
    }

    @Test
    @DisplayName("拒绝数组形式的多个面额")
    void rejectsArrayDenominationId() {
        assertRejected(Map.of("denominationId", List.of(11L, 12L)));
    }

    @Test
    @DisplayName("拒绝 Map 形式的面额参数")
    void rejectsMapDenominationId() {
        assertRejected(Map.of("denominationId", Map.of("id", 11L)));
    }

    @Test
    @DisplayName("拒绝 quantity 字段，避免一次购买多张")
    void rejectsQuantityField() {
        assertRejected(Map.of("denominationId", DENOMINATION_ID, "quantity", 1L));
    }

    @Test
    @DisplayName("拒绝任何额外字段，保持一单一面额契约")
    void rejectsExtraField() {
        assertRejected(Map.of("denominationId", DENOMINATION_ID, "extra", true));
    }

    @Test
    @DisplayName("拒绝空请求体和空面额")
    void rejectsMissingDenominationId() {
        assertRejected(null);
        assertRejected(Map.of());
        assertRejected(Map.of("other", DENOMINATION_ID));
    }

    private void assertRejected(Map<String, Object> body) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.purchase(body));
        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        verify(giftCardService, never()).purchase(anyLong(), anyLong());
        verify(giftCardService, never()).purchase(any(), any());
    }
}
