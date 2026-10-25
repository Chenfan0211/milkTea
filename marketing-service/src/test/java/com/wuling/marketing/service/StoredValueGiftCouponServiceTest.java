package com.wuling.marketing.service;

import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.StoredValuePackageCoupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.StoredValuePackageCouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import com.wuling.marketing.subscribe.MarketingSubscribeNotifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueGiftCouponServiceTest {

    @Test
    void issuesConfiguredQuantityWithUniqueGrantNumbers() {
        StoredValuePackageCouponMapper packageCouponMapper = mock(StoredValuePackageCouponMapper.class);
        CouponMapper couponMapper = mock(CouponMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        MarketingSubscribeNotifier notifier = mock(MarketingSubscribeNotifier.class);
        StoredValueGiftCouponService service = new StoredValueGiftCouponService(
                packageCouponMapper, couponMapper, userCouponMapper, notifier);

        StoredValuePackageCoupon link = new StoredValuePackageCoupon();
        link.setPackageId(1L);
        link.setCouponId(8L);
        link.setCount(2);
        when(packageCouponMapper.selectList(any())).thenReturn(List.of(link));

        Coupon coupon = new Coupon();
        coupon.setId(8L);
        coupon.setName("3元代金券");
        coupon.setStatus("enabled");
        coupon.setValidityType("DAYS");
        coupon.setValidityDays(15);
        coupon.setEffectiveDelayDays(3);
        when(couponMapper.selectById(8L)).thenReturn(coupon);
        when(couponMapper.deductStock(8L)).thenReturn(1);
        doAnswer(invocation -> {
            UserCoupon uc = invocation.getArgument(0);
            uc.setId(Long.valueOf(invocation.getArguments().length));
            return 1;
        }).when(userCouponMapper).insert(any(UserCoupon.class));

        StoredValueGiftCouponService.CouponGrantSummary summary = service.issue(9L, 1L, "CZ202610220001");

        assertEquals(2, summary.quantity(), "套餐配置 2 张时必须实际发放 2 张");
        ArgumentCaptor<UserCoupon> captor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().get(0).getGrantNo().endsWith(":1"));
        assertTrue(captor.getAllValues().get(1).getGrantNo().endsWith(":2"));
        verify(notifier).notifyCouponArrival(
                eq(9L), eq("3元代金券"), eq(2), any(LocalDateTime.class), eq("已放入券包，请及时使用"),
                eq("COUPON_ARRIVAL:STORED_VALUE:CZ202610220001"));
    }

    @Test
    void doesNotFailRechargeWhenGiftCouponStockIsEmpty() {
        StoredValuePackageCouponMapper packageCouponMapper = mock(StoredValuePackageCouponMapper.class);
        CouponMapper couponMapper = mock(CouponMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        MarketingSubscribeNotifier notifier = mock(MarketingSubscribeNotifier.class);
        StoredValueGiftCouponService service = new StoredValueGiftCouponService(
                packageCouponMapper, couponMapper, userCouponMapper, notifier);

        StoredValuePackageCoupon link = new StoredValuePackageCoupon();
        link.setPackageId(1L);
        link.setCouponId(8L);
        link.setCount(1);
        when(packageCouponMapper.selectList(any())).thenReturn(List.of(link));
        Coupon coupon = new Coupon();
        coupon.setId(8L);
        coupon.setName("3元代金券");
        coupon.setStatus("enabled");
        coupon.setValidityType("DAYS");
        coupon.setValidityDays(15);
        when(couponMapper.selectById(8L)).thenReturn(coupon);
        when(couponMapper.deductStock(8L)).thenReturn(0);
        doAnswer(invocation -> {
            UserCoupon uc = invocation.getArgument(0);
            uc.setId(1L);
            return 1;
        }).when(userCouponMapper).insert(any(UserCoupon.class));

        StoredValueGiftCouponService.CouponGrantSummary summary = service.issue(9L, 1L, "CZ-2");

        assertTrue(summary.isEmpty(), "库存不足时不得发券或阻断充值");
        verify(userCouponMapper).deleteById(1L);
    }
}
