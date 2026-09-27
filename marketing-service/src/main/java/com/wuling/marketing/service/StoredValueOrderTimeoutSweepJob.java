package com.wuling.marketing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 储值订单「超时未支付自动关单」兜底定时任务（P0）。
 *
 * <p>背景（真实故障）：CZ202609272359003004 归 0 后不自动取消 ——
 * 储值订单此前根本没有超时关单机制（只有门店订单走 MQ 延迟消息）。
 * 本任务每分钟扫描一次，把超过 15 分钟仍未支付的储值订单关闭，
 * 作为最终兜底，不依赖任何消息中间件。
 */
@Component
public class StoredValueOrderTimeoutSweepJob {

    private static final Logger log = LoggerFactory.getLogger(StoredValueOrderTimeoutSweepJob.class);

    private final StoredValueService storedValueService;
    private final boolean enabled;
    private final int batchSize;

    public StoredValueOrderTimeoutSweepJob(
            StoredValueService storedValueService,
            @Value("${app.stored-value-timeout-scan.enabled:true}") boolean enabled,
            @Value("${app.stored-value-timeout-scan.batch-size:200}") int batchSize) {
        this.storedValueService = storedValueService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.stored-value-timeout-scan.cron:0 * * * * ?}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int closed = storedValueService.closeExpiredUnpaid(batchSize);
            if (closed > 0) {
                log.warn("储值订单超时关单任务本批关闭 {} 条", closed);
            }
        } catch (Exception e) {
            // 单轮失败不阻断后续调度，避免一次 DB 抖动导致关单永久停滞
            log.error("储值订单超时关单任务执行失败", e);
        }
    }
}
