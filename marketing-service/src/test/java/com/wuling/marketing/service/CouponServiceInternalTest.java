package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.AuditLog;
import com.wuling.marketing.entity.Coupon;
import com.wuling.marketing.entity.UserCoupon;
import com.wuling.marketing.mapper.AuditLogMapper;
import com.wuling.marketing.mapper.CouponMapper;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceInternalTest {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final CouponService service = new CouponService(couponMapper, userCouponMapper, auditLogMapper);

    @Test
    @DisplayName("锁券完整校验门店、商品、场景和使用时段，并返回抵扣额")
    void lockValidatesAllRules() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = validCoupon(now);
        coupon.setApplicableStoreIds("[101]");
        coupon.setApplicableProductIds("[1,2]");
        coupon.setScenes("买单、堂食(门店就餐)、堂食(打包外带)");
        coupon.setUsageTime(now.minusMinutes(1).format(TIME) + "~" + now.plusMinutes(1).format(TIME));
        UserCoupon holder = holder(10L, 9L, 20L, CouponService.UNUSED, null);
        when(userCouponMapper.selectByIdForUpdate(10L)).thenReturn(holder);
        when(couponMapper.selectById(20L)).thenReturn(coupon);
        when(userCouponMapper.lockUnused(10L, 123L)).thenReturn(1);

        long discount = service.lock(9L, 10L, "123", 101L, List.of(2L), "dinein", 1200L);

        assertEquals(300L, discount);
        verify(userCouponMapper).lockUnused(10L, 123L);
    }

    @Test
    @DisplayName("空规则 JSON 表示门店和商品均不限")
    void emptyRulesMeanUnlimited() {
        Coupon coupon = validCoupon(LocalDateTime.now());
        coupon.setApplicableStoreIds("[]");
        coupon.setApplicableProductIds("[]");
        coupon.setScenes(null);
        coupon.setUsageTime(null);
        UserCoupon holder = holder(11L, 9L, 20L, CouponService.UNUSED, null);
        when(userCouponMapper.selectByIdForUpdate(11L)).thenReturn(holder);
        when(couponMapper.selectById(20L)).thenReturn(coupon);
        when(userCouponMapper.lockUnused(11L, 123L)).thenReturn(1);

        assertEquals(300L, service.lock(9L, 11L, "123", 999L, List.of(999L), "pickup", 1000L));
    }

    @Test
    @DisplayName("usage_time 支持跨午夜区间")
    void crossMidnightUsageTimeAllowed() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = validCoupon(now);
        LocalTime current = LocalTime.now();
        LocalTime windowStart = current.minusHours(1);
        LocalTime windowEnd = current.minusHours(2);
        if (!windowStart.isAfter(windowEnd)) {
            windowStart = current.plusHours(1);
            windowEnd = current.plusMinutes(30);
        }
        coupon.setUsageTime(windowStart.format(TIME) + "~" + windowEnd.format(TIME));
        UserCoupon holder = holder(12L, 9L, 20L, CouponService.UNUSED, null);
        when(userCouponMapper.selectByIdForUpdate(12L)).thenReturn(holder);
        when(couponMapper.selectById(20L)).thenReturn(coupon);
        when(userCouponMapper.lockUnused(12L, 123L)).thenReturn(1);

        assertEquals(300L, service.lock(9L, 12L, "123", null, List.of(), "dinein", 1000L));
    }

    @Test
    @DisplayName("门槛不满足时不发生状态更新")
    void thresholdRejected() {
        Coupon coupon = validCoupon(LocalDateTime.now());
        coupon.setThreshold(1000L);
        UserCoupon holder = holder(13L, 9L, 20L, CouponService.UNUSED, null);
        when(userCouponMapper.selectByIdForUpdate(13L)).thenReturn(holder);
        when(couponMapper.selectById(20L)).thenReturn(coupon);

        assertThrows(BusinessException.class,
                () -> service.lock(9L, 13L, "123", null, List.of(), "dinein", 999L));
        verify(userCouponMapper, never()).lockUnused(anyLong(), anyLong());
    }

    @Test
    @DisplayName("适用商品存在时调用商品列表必须有交集")
    void productMustMatch() {
        Coupon coupon = validCoupon(LocalDateTime.now());
        coupon.setApplicableProductIds("[1,2]");
        UserCoupon holder = holder(14L, 9L, 20L, CouponService.UNUSED, null);
        when(userCouponMapper.selectByIdForUpdate(14L)).thenReturn(holder);
        when(couponMapper.selectById(20L)).thenReturn(coupon);

        assertThrows(BusinessException.class,
                () -> service.lock(9L, 14L, "123", null, List.of(3L), "dinein", 1000L));
    }

    @Test
    @DisplayName("同一订单重复锁券返回原抵扣额，不重复更新")
    void repeatedLockForSameOrderIsIdempotent() {
        Coupon coupon = validCoupon(LocalDateTime.now());
        UserCoupon unused = holder(15L, 9L, 20L, CouponService.UNUSED, null);
        UserCoupon locked = holder(15L, 9L, 20L, CouponService.LOCKED, 123L);
        when(userCouponMapper.selectByIdForUpdate(15L)).thenReturn(unused, locked);
        when(couponMapper.selectById(20L)).thenReturn(coupon);
        when(userCouponMapper.lockUnused(15L, 123L)).thenReturn(1);

        assertEquals(300L, service.lock(9L, 15L, "123", null, List.of(), "dinein", 1000L));
        assertEquals(300L, service.lock(9L, 15L, "123", null, List.of(), "dinein", 1000L));

        verify(userCouponMapper, times(1)).lockUnused(15L, 123L);
    }

    @Test
    @DisplayName("并发锁同一张券时条件更新只允许一次成功")
    void concurrentLockAllowsOnlyOneSuccess() throws Exception {
        Coupon coupon = validCoupon(LocalDateTime.now());
        when(userCouponMapper.selectByIdForUpdate(16L))
                .thenAnswer(invocation -> holder(16L, 9L, 20L, CouponService.UNUSED, null));
        when(couponMapper.selectById(20L)).thenReturn(coupon);
        AtomicInteger updates = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        when(userCouponMapper.lockUnused(16L, 123L)).thenAnswer(invocation -> {
            ready.countDown();
            go.await(3, TimeUnit.SECONDS);
            return updates.getAndIncrement() == 0 ? 1 : 0;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = pool.submit(() -> service.lock(9L, 16L, "123", null, List.of(), "dinein", 1000L));
            Future<Long> second = pool.submit(() -> service.lock(9L, 16L, "123", null, List.of(), "dinein", 1000L));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            go.countDown();

            int success = 0;
            int rejected = 0;
            for (Future<Long> future : List.of(first, second)) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    rejected++;
                }
            }
            assertEquals(1, success);
            assertEquals(1, rejected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发重复核销最终仍返回幂等成功")
    void consumeRechecksStateAfterConditionalUpdateLosesRace() {
        UserCoupon locked = holder(17L, 9L, 20L, CouponService.LOCKED, 123L);
        UserCoupon used = holder(17L, 9L, 20L, CouponService.USED, 123L);
        when(userCouponMapper.selectByIdForUpdate(17L)).thenReturn(locked, used);
        when(userCouponMapper.consumeLocked(17L, 123L)).thenReturn(0);

        assertTrue(service.consume(9L, 17L, "123"));
    }

    @Test
    @DisplayName("退款恢复必须校验券确实属于该订单")
    void restoreRejectsCouponFromAnotherOrder() {
        Coupon coupon = validCoupon(LocalDateTime.now());
        UserCoupon used = holder(18L, 9L, 20L, CouponService.USED, 123L);
        when(userCouponMapper.selectByIdForUpdate(18L)).thenReturn(used);
        when(couponMapper.selectById(20L)).thenReturn(coupon);

        assertThrows(BusinessException.class,
                () -> service.restoreAfterRefund(9L, 18L, "456"));
        verify(userCouponMapper, never()).restoreAfterRefund(anyLong(), any());
    }

    @Test
    @DisplayName("同模板已有 active 新券时原券置 EXPIRED 且审计写入失败不得吞掉")
    void restoreConflictAuditFailureRollsBack() {
        Coupon coupon = validCoupon(LocalDateTime.now());
        UserCoupon used = holder(19L, 9L, 20L, CouponService.USED, 123L);
        UserCoupon another = holder(20L, 9L, 20L, CouponService.UNUSED, null);
        when(userCouponMapper.selectByIdForUpdate(19L)).thenReturn(used);
        when(couponMapper.selectById(20L)).thenReturn(coupon);
        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(used, another));
        when(auditLogMapper.insert(any(AuditLog.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.restoreAfterRefund(9L, 19L, "123"));
        verify(userCouponMapper, never()).restoreAfterRefund(anyLong(), any());
    }

    private Coupon validCoupon(LocalDateTime now) {
        Coupon coupon = new Coupon();
        coupon.setId(20L);
        coupon.setStatus("enabled");
        coupon.setValidityType("RANGE");
        coupon.setValidityStart(now.minusDays(1));
        coupon.setValidityEnd(now.plusDays(1));
        coupon.setAmount(300L);
        coupon.setThreshold(0L);
        return coupon;
    }

    private UserCoupon holder(Long id, Long userId, Long couponId, String status, Long lockOrderId) {
        UserCoupon holder = new UserCoupon();
        holder.setId(id);
        holder.setUserId(userId);
        holder.setCouponId(couponId);
        holder.setStatus(status);
        holder.setLockOrderId(lockOrderId);
        holder.setReceiveTime(LocalDateTime.now().minusDays(1));
        return holder;
    }
}