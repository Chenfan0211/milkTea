package com.wuling.finance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 资金对账定时任务（第 8 期）。
 *
 * <p>每小时执行一次，检查 MQ 最终一致链路的遗漏：
 * <ul>
 *   <li>已核销但无分账快照（分账事件丢失）</li>
 *   <li>已退款但未冲正（冲正事件丢失）</li>
 *   <li>分账五方金额与实付不一致</li>
 * </ul>
 *
 * <p>频率说明：小时级而非天级 —— 资金不一致发现得越早，
 * 人工介入的成本越低（尤其「未分账」场景下各方还没动钱）。
 *
 * <p>可通过 {@code app.reconcile.enabled=false} 关闭（如排查期间避免干扰），
 * cron 可用 {@code app.reconcile.cron} 覆盖。
 */
@Component
public class ReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcileJob.class);

    private final ReconcileService reconcileService;
    private final boolean enabled;

    public ReconcileJob(ReconcileService reconcileService,
                        @Value("${app.reconcile.enabled:true}") boolean enabled) {
        this.reconcileService = reconcileService;
        this.enabled = enabled;
    }

    /** 每小时第 10 分钟执行（避开整点的结算任务） */
    @Scheduled(cron = "${app.reconcile.cron:0 10 * * * ?}")
    public void reconcileHourly() {
        if (!enabled) {
            return;
        }
        try {
            int found = reconcileService.reconcile();
            if (found == 0) {
                log.info("对账完成，未发现资金不一致");
            }
        } catch (Exception e) {
            // 对账失败不应影响其他定时任务
            log.error("对账任务执行失败", e);
        }
    }
}
