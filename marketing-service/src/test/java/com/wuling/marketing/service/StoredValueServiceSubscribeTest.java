package com.wuling.marketing.service;

import com.wuling.marketing.entity.StoredValueOrder;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.StoredValueOrderMapper;
import com.wuling.marketing.mapper.StoredValuePackageCouponMapper;
import com.wuling.marketing.mapper.StoredValuePackageMapper;
import com.wuling.marketing.mapper.StoredValueTxnMapper;
import com.wuling.marketing.subscribe.MarketingSubscribeNotifier;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueServiceSubscribeTest {

    @Test
    void markPaidRegistersRechargeAndGiftCouponAfterCommit() {
        StoredValuePackageMapper packageMapper = mock(StoredValuePackageMapper.class);
        StoredValuePackageCouponMapper packageCouponMapper = mock(StoredValuePackageCouponMapper.class);
        StoredValueOrderMapper orderMapper = mock(StoredValueOrderMapper.class);
        CouponMapper couponMapper = mock(CouponMapper.class);
        AppUserMapper appUserMapper = mock(AppUserMapper.class);
        StoredValueTxnMapper txnMapper = mock(StoredValueTxnMapper.class);
        MarketingSubscribeNotifier notifier = mock(MarketingSubscribeNotifier.class);
        StoredValueGiftCouponService giftCouponService = mock(StoredValueGiftCouponService.class);

        StoredValueOrder unpaid = new StoredValueOrder();
        unpaid.setId(1L);
        unpaid.setOrderNo("CZ-1");
        unpaid.setUserId(9L);
        unpaid.setPackageId(8L);
        unpaid.setStoreSubjectId(101L);
        unpaid.setAmount(10000L);
        unpaid.setPayStatus(StoredValueService.PAY_UNPAID);
        StoredValueOrder paid = new StoredValueOrder();
        paid.setOrderNo("CZ-1");
        paid.setUserId(9L);
        paid.setPackageId(8L);
        paid.setStoreSubjectId(101L);
        paid.setAmount(10000L);
        paid.setPayStatus(StoredValueService.PAY_PAID);
        paid.setUpdateTime(LocalDateTime.of(2026, 10, 22, 17, 0));
        when(orderMapper.selectOne(any())).thenReturn(unpaid, paid);
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(appUserMapper.addBalance(9L, 10000L)).thenReturn(1);

        StoredValueService service = new StoredValueService(
                packageMapper, packageCouponMapper, orderMapper, couponMapper, appUserMapper, txnMapper);
        ReflectionTestUtils.setField(service, "subscribeNotifier", notifier);
        ReflectionTestUtils.setField(service, "giftCouponService", giftCouponService);

        service.markPaid("CZ-1", "wx-tx", "openid", 10000L);

        verify(notifier).notifyStoredValueRecharge(9L, 101L, 10000L,
                LocalDateTime.of(2026, 10, 22, 17, 0), "CZ-1");
        verify(giftCouponService).issue(9L, 8L, "CZ-1");
    }
}
