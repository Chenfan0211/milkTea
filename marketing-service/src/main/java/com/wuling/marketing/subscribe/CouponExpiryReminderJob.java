package com.wuling.marketing.subscribe;

import com.wuling.marketing.dto.CouponExpiryCandidate;
import com.wuling.marketing.mapper.UserCouponMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 优惠券到期前提醒任务：按用户 + 到期日汇合发送。 */
@Component
public class CouponExpiryReminderJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpiryReminderJob.class);

    private final UserCouponMapper userCouponMapper;
    private final MarketingSubscribeNotifier notifier;
    private final boolean enabled;
    private final int daysBefore;
    private final int batchSize;

    public CouponExpiryReminderJob(UserCouponMapper userCouponMapper,
                                   MarketingSubscribeNotifier notifier,
                                   @Value("${app.wx.coupon-expiry.enabled:true}") boolean enabled,
                                   @Value("${app.wx.coupon-expiry.days-before:3}") int daysBefore,
                                   @Value("${app.wx.coupon-expiry.batch-size:500}") int batchSize) {
        this.userCouponMapper = userCouponMapper;
        this.notifier = notifier;
        this.enabled = enabled;
        this.daysBefore = Math.max(1, daysBefore);
        this.batchSize = batchSize > 0 ? batchSize : 500;
    }

    /** 事务内登记 outbox；任一入队失败整批回滚，下一次调度可安全重试。 */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "${app.wx.coupon-expiry.cron:0 0 10 * * ?}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        LocalDate targetDate = LocalDate.now().plusDays(daysBefore);
        List<CouponExpiryCandidate> rows = userCouponMapper.selectExpiringUnusedOn(targetDate, batchSize);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<String, List<CouponExpiryCandidate>> groups = new LinkedHashMap<>();
        for (CouponExpiryCandidate row : rows) {
            if (row == null || row.getUserId() == null || row.getExpireAt() == null) {
                continue;
            }
            String key = row.getUserId() + ":" + row.getExpireAt().toLocalDate();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        int sent = 0;
        for (List<CouponExpiryCandidate> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            group.sort(Comparator.comparing(CouponExpiryCandidate::getAmount,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(CouponExpiryCandidate::getExpireAt)
                    .thenComparing(CouponExpiryCandidate::getId));
            CouponExpiryCandidate primary = group.get(0);
            String tip = group.size() > 1
                    ? "另有" + (group.size() - 1) + "张券即将过期，请及时使用"
                    : "请及时使用";
            String messageKey = "COUPON_EXPIRE:" + primary.getUserId() + ":"
                    + primary.getExpireAt().toLocalDate();
            notifier.notifyCouponExpiry(
                    primary.getUserId(),
                    couponName(primary),
                    primary.getAmount() == null ? 0L : primary.getAmount(),
                    primary.getExpireAt(),
                    tip,
                    messageKey);
            sent += 1;
        }
        log.info("优惠券到期提醒任务完成 targetDate={} groups={}", targetDate, sent);
    }

    private static String couponName(CouponExpiryCandidate candidate) {
        return StringUtils.hasText(candidate.getCouponName()) ? candidate.getCouponName() : "优惠券";
    }
}
