package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.exception.BusinessException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponServiceTest {

    private final CouponMapper couponMapper = mock(CouponMapper.class);
    private final UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
    private final CouponService service = new CouponService(couponMapper, userCouponMapper);

    @Test
    @DisplayName("我的可用券只返回 UNUSED 且当前有效券，并带模板展示字段")
    void myCouponsReturnsOnlyEffectiveUnusedCouponsWithTemplateMetadata() {
        LocalDateTime now = LocalDateTime.now();
        Coupon daysCoupon = coupon(11L, "DAYS", now.minusDays(1), null, 7);
        Coupon expiredCoupon = coupon(12L, "DAYS", now.minusDays(10), null, 3);
        Coupon rangeCoupon = coupon(13L, "RANGE", now.minusDays(1), now.plusDays(2), null);

        UserCoupon valid = userCoupon(101L, 1L, 11L, "UNUSED", now.minusDays(1));
        UserCoupon used = userCoupon(102L, 1L, 11L, "USED", now.minusDays(1));
        UserCoupon locked = userCoupon(103L, 1L, 11L, "LOCKED", now.minusDays(1));
        UserCoupon expired = userCoupon(104L, 1L, 12L, "UNUSED", now.minusDays(10));
        UserCoupon rangeValid = userCoupon(105L, 1L, 13L, "UNUSED", now.minusDays(1));

        when(userCouponMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(valid, used, locked, expired, rangeValid));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(daysCoupon, expiredCoupon, rangeCoupon));

        List<UserCouponView> result = service.myCoupons(1L, CouponService.UNUSED);

        assertEquals(2, result.size(), "只应保留当前有效的 UNUSED 券");
        UserCouponView first = result.get(0);
        assertEquals(101L, first.getId(), "展示 ID 必须是用户券主键，不是模板 code");
        assertEquals("可用券11", first.getName());
        assertEquals(300L, first.getAmount());
        assertEquals(2000L, first.getThreshold());
        assertNotNull(first.getExpireAt());
        assertTrue(first.getUsable());
        assertEquals("DAYS", first.getValidityType());
        assertTrue(result.stream().anyMatch(item -> item.getId().equals(105L)));
        assertFalse(result.stream().anyMatch(item -> item.getId().equals(102L) || item.getId().equals(103L) || item.getId().equals(104L)));
    }

    @Test
    @DisplayName("同一模板的多个用户券不能因模板 code 重复而合并")
    void duplicateTemplateCouponsKeepSeparateUserCouponIds() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(21L, "RANGE", now.minusDays(1), now.plusDays(1), null);
        UserCoupon first = userCoupon(201L, 1L, 21L, "UNUSED", now.minusHours(2));
        UserCoupon second = userCoupon(202L, 1L, 21L, "UNUSED", now.minusHours(1));
        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(coupon));

        List<UserCouponView> result = service.myCoupons(1L, CouponService.UNUSED);

        assertEquals(2, result.size());
        assertEquals(201L, result.get(0).getId());
        assertEquals(202L, result.get(1).getId());
    }

    @Test
    @DisplayName("待生效券走 PENDING 虚拟状态：未到生效时间的 RANGE 券归入待生效，已生效的不归入")
    void myCouponsPendingReturnsOnlyNotStartedCoupons() {
        LocalDateTime now = LocalDateTime.now();
        // 未来才生效的 RANGE 券
        Coupon futureRange = coupon(51L, "RANGE", now.plusDays(2), now.plusDays(5), null);
        // 已生效的 RANGE 券
        Coupon activeRange = coupon(52L, "RANGE", now.minusDays(1), now.plusDays(2), null);

        UserCoupon pending = userCoupon(501L, 1L, 51L, "UNUSED", now.minusDays(1));
        UserCoupon active = userCoupon(502L, 1L, 52L, "UNUSED", now.minusDays(1));

        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(pending, active));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(futureRange, activeRange));

        List<UserCouponView> result = service.myCoupons(1L, CouponService.PENDING);

        assertEquals(1, result.size(), "PENDING 只应返回未到生效时间的券");
        assertEquals(501L, result.get(0).getId());
        assertEquals("UNUSED", result.get(0).getStatus());
        assertTrue(result.get(0).getUsable());
    }

    @Test
    @DisplayName("真实状态 USED/EXPIRED 直接按库表状态透传，不再被可用性过滤吞掉")
    void myCouponsExplicitTerminalStatusReturnsRowsDirectly() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(61L, "DAYS", now.minusDays(10), null, 3);
        UserCoupon used = userCoupon(601L, 1L, 61L, "USED", now.minusDays(10));
        UserCoupon expired = userCoupon(602L, 1L, 61L, "EXPIRED", now.minusDays(10));

        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(used, expired));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(coupon));

        List<UserCouponView> result = service.myCoupons(1L, CouponService.USED);

        assertEquals(1, result.size());
        assertEquals(601L, result.get(0).getId());
        assertEquals("USED", result.get(0).getStatus());
        assertFalse(result.get(0).getUsable());
    }

    @Test
    @DisplayName("锁券与列表共用有效期判定：已过期券不能锁定")
    void lockRejectsExpiredCoupon() {
        LocalDateTime now = LocalDateTime.now();
        Coupon expiredCoupon = coupon(31L, "DAYS", now.minusDays(10), null, 3);
        UserCoupon expired = userCoupon(301L, 1L, 31L, "UNUSED", now.minusDays(10));
        when(userCouponMapper.selectByIdForUpdate(301L)).thenReturn(expired);
        when(couponMapper.selectById(31L)).thenReturn(expiredCoupon);

        BusinessException error = assertThrows(BusinessException.class, () -> service.lock(1L, 301L, 9001L, 10000L));
        assertEquals("优惠券已过期或不可用", error.getMessage());
        verify(userCouponMapper, never()).updateById(any(UserCoupon.class));
    }

    @Test
    @DisplayName("延迟生效券：领取 3 天内归入 PENDING 待生效，可用券 Tab 不返回")
    void delayedCouponIsPendingUntilEffective() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = delayedCoupon(71L, 3, 15);
        // 刚领取：生效起点是 3 天后
        UserCoupon justReceived = userCoupon(701L, 1L, 71L, "UNUSED", now.minusHours(1));
        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(justReceived));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(coupon));

        List<UserCouponView> pending = service.myCoupons(1L, CouponService.PENDING);
        assertEquals(1, pending.size(), "领取未满 3 天的券必须归入待生效");

        List<UserCouponView> unused = service.myCoupons(1L, CouponService.UNUSED);
        assertTrue(unused.isEmpty(), "待生效券不得出现在可用券列表");
    }

    @Test
    @DisplayName("延迟生效券：领取满 3 天后归入可用券，且过期时间 = 生效时间 + 有效天数")
    void delayedCouponBecomesUsableAfterDelay() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = delayedCoupon(72L, 3, 15);
        // 4 天前领取：已过 3 天延迟，进入可用期
        UserCoupon received = userCoupon(702L, 1L, 72L, "UNUSED", now.minusDays(4));
        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(received));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(coupon));

        List<UserCouponView> unused = service.myCoupons(1L, CouponService.UNUSED);
        assertEquals(1, unused.size(), "满 3 天后必须可用");
        assertTrue(unused.get(0).getUsable());
        // 过期时间 = 领取时间 + 3 + 15 = 领取时间 + 18 天
        assertEquals(now.minusDays(4).plusDays(18).withNano(0),
                unused.get(0).getExpireAt().withNano(0),
                "过期时间应为 生效时间(领取+3天) + 有效15天");

        List<UserCouponView> pending = service.myCoupons(1L, CouponService.PENDING);
        assertTrue(pending.isEmpty(), "已生效的券不得再出现在待生效列表");
    }

    @Test
    @DisplayName("无延迟券（effectiveDelayDays 为空）保持立即生效")
    void couponWithoutDelayIsImmediatelyUsable() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(73L, "DAYS", null, null, 15);
        UserCoupon received = userCoupon(703L, 1L, 73L, "UNUSED", now.minusHours(1));
        when(userCouponMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(received));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(coupon));

        List<UserCouponView> unused = service.myCoupons(1L, CouponService.UNUSED);
        assertEquals(1, unused.size(), "无延迟券必须立即可用");
        assertTrue(unused.get(0).getUsable());
    }

    @Test
    @DisplayName("未选状态时也按可用性过滤，未知有效期类型不误伤无期限券")
    void myCouponsWithoutStatusStillFiltersByUsable() {
        LocalDateTime now = LocalDateTime.now();
        Coupon valid = coupon(41L, null, null, null, null);
        UserCoupon coupon = userCoupon(401L, 1L, 41L, "UNUSED", now);
        when(userCouponMapper.selectList(any(Wrapper.class))).thenReturn(List.of(coupon));
        when(couponMapper.selectBatchIds(anyCollection())).thenReturn(List.of(valid));

        List<UserCouponView> result = service.myCoupons(1L, null);

        assertEquals(1, result.size());
        assertTrue(result.get(0).getUsable());
    }

    /** 带延迟生效天数的券模板。 */
    private Coupon delayedCoupon(Long id, Integer delayDays, Integer validityDays) {
        Coupon coupon = coupon(id, "DAYS", null, null, validityDays);
        coupon.setEffectiveDelayDays(delayDays);
        return coupon;
    }

    private Coupon coupon(Long id, String validityType, LocalDateTime start,
                          LocalDateTime end, Integer days) {
        Coupon coupon = new Coupon();
        coupon.setId(id);
        coupon.setCode("coupon-" + id);
        coupon.setName("可用券" + id);
        coupon.setType("voucher");
        coupon.setAmount(300L);
        coupon.setThreshold(2000L);
        coupon.setStatus("enabled");
        coupon.setValidityType(validityType);
        coupon.setValidityStart(start);
        coupon.setValidityEnd(end);
        coupon.setValidityDays(days);
        return coupon;
    }

    private UserCoupon userCoupon(Long id, Long userId, Long couponId, String status, LocalDateTime receiveTime) {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setId(id);
        userCoupon.setUserId(userId);
        userCoupon.setCouponId(couponId);
        userCoupon.setStatus(status);
        userCoupon.setReceiveTime(receiveTime);
        return userCoupon;
    }
}
