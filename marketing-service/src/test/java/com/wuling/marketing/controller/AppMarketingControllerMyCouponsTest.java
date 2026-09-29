package com.wuling.marketing.controller;

import com.wuling.common.api.Result;
import com.wuling.marketing.dto.UserCouponView;
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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppMarketingControllerMyCouponsTest {

    private static final Long USER_ID = 7L;

    private CouponService couponService;
    private AppMarketingController controller;

    @BeforeEach
    void setUp() {
        couponService = mock(CouponService.class);
        controller = new AppMarketingController(
                couponService,
                mock(StoredValueService.class),
                mock(GiftCardService.class),
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
    @DisplayName("待生效 Tab：status=PENDING 透传给服务层，userId 取自 JWT 而非路径参数")
    void myCouponsPassesPendingStatusAndIgnoresPathUserId() {
        List<UserCouponView> pendingCoupons = List.of(new UserCouponView());
        when(couponService.myCoupons(USER_ID, CouponService.PENDING)).thenReturn(pendingCoupons);

        // 路径 userId 故意传 0（兼容占位），验证归属以 token 为准。
        Result<List<UserCouponView>> result = controller.myCoupons(0L, CouponService.PENDING);

        assertSame(pendingCoupons, result.getData());
        verify(couponService).myCoupons(USER_ID, CouponService.PENDING);
    }

    @Test
    @DisplayName("未使用 Tab：status=UNUSED 透传")
    void myCouponsPassesUnusedStatus() {
        List<UserCouponView> unusedCoupons = List.of(new UserCouponView());
        when(couponService.myCoupons(USER_ID, CouponService.UNUSED)).thenReturn(unusedCoupons);

        Result<List<UserCouponView>> result = controller.myCoupons(USER_ID, CouponService.UNUSED);

        assertSame(unusedCoupons, result.getData());
        verify(couponService).myCoupons(USER_ID, CouponService.UNUSED);
    }

    @Test
    @DisplayName("终态 Tab：status=EXPIRED 透传")
    void myCouponsPassesExpiredStatus() {
        List<UserCouponView> expiredCoupons = List.of(new UserCouponView());
        when(couponService.myCoupons(USER_ID, CouponService.EXPIRED)).thenReturn(expiredCoupons);

        Result<List<UserCouponView>> result = controller.myCoupons(USER_ID, CouponService.EXPIRED);

        assertSame(expiredCoupons, result.getData());
        verify(couponService).myCoupons(USER_ID, CouponService.EXPIRED);
    }
}
