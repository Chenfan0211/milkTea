package com.wuling.marketing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 优惠券「过期未用」归档扫描（兜底任务）。
 *
 * <p><b>为什么只是兜底</b>：DAYS 类型券（领取后 N 天）在库里没有过期时间落点，
 * 「已过期」Tab 由 {@code CouponService#myCoupons} 的 {@code deriveTab} <b>现算</b>，
 * 所以即使本任务停摆，用户看到的分类依然正确。本任务只把过期券落库成 EXPIRED，
 * 让状态可索引、可统计、可对账（口径与列表判定保持一致：含延迟生效天数）。
 *
 * <p>写法对齐既有的 {@link StoredValueOrderTimeoutSweepJob}：单轮失败只记日志，
 * 不阻断后续调度。
 */
@Component
public class CouponExpireSweepJob {

    private static final Logger log = LoggerFactory.getLogger(CouponExpireSweepJob.class);

    private final CouponService couponService;
    private final boolean enabled;
    private final int batchSize;

    public CouponExpireSweepJob(CouponService couponService,
                                @Value("${app.coupon-expire-scan.enabled:true}") boolean enabled,
                                @Value("${app.coupon-expire-scan.batch-size:500}") int batchSize) {
        this.couponService = couponService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.coupon-expire-scan.cron:0 */5 * * * ?}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int archived = couponService.archiveOverdueUnused(batchSize);
            if (archived > 0) {
                log.info("优惠券过期归档任务本批归档 {} 张", archived);
            }
        } catch (Exception e) {
            // 单轮失败不阻断后续调度：展示层不依赖本任务，不会因此让用户看到错分类
            log.error("优惠券过期归档任务执行失败", e);
        }
    }
}