package com.wuling.marketing.service;

import com.wuling.marketing.mapper.ReferralRecordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 邀请首单奖励「漏发补发」兜底定时任务。
 *
 * <p>背景（真实缺陷）：奖励发放只由订单支付成功的 MQ 事件触发，
 * 且 {@code rewardFirstOrder} 要求被邀请人支付那一刻
 * {@code app_user.referrer_id} 已绑定。若好友「先完成首单、后绑定邀请人」
 * （或注册时邀请人解析失败），该订单的奖励会被永久跳过 —— MQ 不会重放历史订单。
 *
 * <p>本任务定期扫描「已绑定邀请人、已有已支付订单、但无 referral_record」的用户，
 * 用其最早一笔已支付订单号补建记录并发奖。依赖
 * {@link ReferralRewardService#rewardFirstOrder} 自身的幂等（referral_record
 * 唯一键 + user_coupon 唯一索引），因此可安全重复执行。
 */
@Component
public class ReferralRewardBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardBackfillJob.class);

    private final ReferralRecordMapper referralRecordMapper;
    private final ReferralRewardService referralRewardService;
    private final boolean enabled;
    private final int batchSize;

    @Autowired
    public ReferralRewardBackfillJob(
            ReferralRecordMapper referralRecordMapper,
            ReferralRewardService referralRewardService,
            @Value("${app.referral-reward-backfill.enabled:true}") boolean enabled,
            @Value("${app.referral-reward-backfill.batch-size:200}") int batchSize) {
        this.referralRecordMapper = referralRecordMapper;
        this.referralRewardService = referralRewardService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    /** 测试用构造：默认开启，批大小 200。 */
    public ReferralRewardBackfillJob(ReferralRecordMapper referralRecordMapper,
                                     ReferralRewardService referralRewardService,
                                     boolean enabled) {
        this(referralRecordMapper, referralRewardService, enabled, 200);
    }

    @Scheduled(cron = "${app.referral-reward-backfill.cron:0 30 * * * ?}")
    public void run() {
        sweep();
    }

    /** 单轮扫描，便于测试与运维手动触发；返回成功补发条数。 */
    public int sweep() {
        if (!enabled) {
            return 0;
        }
        int rewarded = 0;
        try {
            List<Map<String, Object>> candidates =
                    referralRecordMapper.selectMissingRewardCandidates(Math.max(1, batchSize));
            if (candidates == null || candidates.isEmpty()) {
                return 0;
            }
            for (Map<String, Object> row : candidates) {
                Long inviteeUserId = asLong(row.get("inviteeUserId"));
                Object orderNoValue = row.get("orderNo");
                String orderNo = orderNoValue == null ? null : String.valueOf(orderNoValue);
                if (inviteeUserId == null || orderNo == null || orderNo.isBlank()) {
                    continue;
                }
                try {
                    // 复用正常发奖链路：内部按 referral_record 唯一键幂等，重复执行安全。
                    // 返回 true 才计入本批成功数（无邀请人 / 已发过会返回 false）。
                    if (referralRewardService.rewardFirstOrder(inviteeUserId, orderNo)) {
                        rewarded++;
                    }
                } catch (Exception e) {
                    // 单条失败不影响其余候选，下一轮继续重试。
                    log.warn("邀请首单补发单条失败 inviteeUserId={} orderNo={}",
                            inviteeUserId, orderNo, e);
                }
            }
            if (rewarded > 0) {
                log.warn("邀请首单奖励补发任务本批处理 {} 条", rewarded);
            }
        } catch (Exception e) {
            // 单轮失败不阻断调度，避免一次 DB 抖动导致补发永久停滞。
            log.error("邀请首单奖励补发任务执行失败", e);
        }
        return rewarded;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
