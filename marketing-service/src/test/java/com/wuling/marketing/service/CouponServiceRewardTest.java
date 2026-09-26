package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceRewardTest {

    @Test
    @DisplayName("重复持有奖励券：视为已发放，不扣库存、不抛异常")
    void duplicateActiveCouponIsIdempotent() {
        CouponMapper couponMapper = mock(CouponMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        Coupon coupon = new Coupon();
        coupon.setId(9L);
        coupon.setCode("referral-coupon-3");
        coupon.setAmount(300L);
        coupon.setStock(100);
        coupon.setStatus("enabled");
        when(couponMapper.selectOne(any(Wrapper.class))).thenReturn(coupon);
        when(userCouponMapper.insertIgnoreActive(10L, 9L)).thenReturn(0);
        CouponService service = new CouponService(couponMapper, userCouponMapper);

        assertDoesNotThrow(() -> service.issueReferralCoupon(10L, 300L));

        verify(couponMapper, never()).deductStock(any());
    }
}