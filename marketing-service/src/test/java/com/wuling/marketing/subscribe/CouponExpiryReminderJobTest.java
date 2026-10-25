package com.wuling.marketing.subscribe;

import com.wuling.marketing.dto.CouponExpiryCandidate;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponExpiryReminderJobTest {

    @Test
    void groupsSameUserAndExpiryDateAndChoosesHighestAmount() {
        UserCouponMapper mapper = mock(UserCouponMapper.class);
        MarketingSubscribeNotifier notifier = mock(MarketingSubscribeNotifier.class);
        LocalDate targetDate = LocalDate.now().plusDays(3);
        LocalDateTime expireAt = targetDate.atTime(23, 59);
        when(mapper.selectExpiringUnusedOn(eq(targetDate), anyInt())).thenReturn(List.of(
                candidate(1L, 9L, "3元券", 300L, expireAt),
                candidate(2L, 9L, "5元券", 500L, expireAt)));

        new CouponExpiryReminderJob(mapper, notifier, true, 3, 500).sweep();

        verify(notifier).notifyCouponExpiry(
                9L,
                "5元券",
                500L,
                expireAt,
                "另有1张券即将过期，请及时使用",
                "COUPON_EXPIRE:9:" + targetDate);
    }

    private CouponExpiryCandidate candidate(Long id, Long userId, String name, Long amount, LocalDateTime expireAt) {
        CouponExpiryCandidate candidate = new CouponExpiryCandidate();
        candidate.setId(id);
        candidate.setUserId(userId);
        candidate.setCouponName(name);
        candidate.setAmount(amount);
        candidate.setExpireAt(expireAt);
        return candidate;
    }
}
