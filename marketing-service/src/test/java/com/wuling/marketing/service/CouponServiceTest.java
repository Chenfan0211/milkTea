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
    @DisplayName("锁券与列表共用有效期判定：已过期券不能锁定")
    void lockRejectsExpiredCoupon() {
        LocalDateTime now = LocalDateTime.now();
        Coupon expiredCoupon = coupon(31L, "DAYS", now.minusDays(10), null, 3);
        UserCoupon expired = userCoupon(301L, 1L, 31L, "UNUSED", now.minusDays(10));
        when(userCouponMapper.selectById(301L)).thenReturn(expired);
        when(couponMapper.selectById(31L)).thenReturn(expiredCoupon);

        assertThrows(BusinessException.class, () -> service.lock(1L, 301L, 9001L, 10000L));
        verify(userCouponMapper, never()).updateById(any(UserCoupon.class));
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
