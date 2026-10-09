package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.RefundMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 微信退款「主动查询兜底」定时任务。
 *
 * <p>背景：微信退款是异步的，最终状态依赖退款结果通知回调，
 * 一旦回调丢失（网络抖动 / https 域名临时不可用），退款单会永久卡在 REFUNDING。
 * 本任务定期扫描超时的 REFUNDING 单，主动查三方真实退款状态并推进，
 * 形成「回调为主、主动查询为辅」的双保险。
 *
 * <p>mock 通道下 {@code queryRefund} 返回 null，本任务自然跳过（0 推进），
 * 对现有链路零影响；待备案切 wxpay 后自动生效。
 */
@Component
public class RefundStatusReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(RefundStatusReconcileJob.class);

    /** 退款单超过该分钟数仍未终态才主动查询，避免与正常回调抢跑 */
    private static final long QUERY_AFTER_MINUTES = 5;

    private final RefundMapper refundMapper;
    private final RefundService refundService;
    private final boolean enabled;
    private final int batchSize;

    public RefundStatusReconcileJob(
            RefundMapper refundMapper,
            RefundService refundService,
            @Value("${app.refund-reconcile.enabled:true}") boolean enabled,
            @Value("${app.refund-reconcile.batch-size:100}") int batchSize) {
        this.refundMapper = refundMapper;
        this.refundService = refundService;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${app.refund-reconcile.cron:0 */5 * * * ?}")
    public void run() {
        sweep();
    }

    /** 单轮扫描，便于测试与运维手动触发。 */
    public int sweep() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(QUERY_AFTER_MINUTES);
        List<Refund> pendings = refundMapper.selectList(
                new LambdaQueryWrapper<Refund>()
                        .eq(Refund::getStatus, RefundService.STATUS_REFUNDING)
                        .lt(Refund::getApplyTime, deadline)
                        .last("limit " + Math.max(1, batchSize)));
        int advanced = 0;
        for (Refund refund : pendings) {
            try {
                if (refundService.syncRefundStatus(refund.getOrderNo(), refund.getRefundNo())) {
                    advanced++;
                }
            } catch (Exception e) {
                log.warn("退款状态主动查询单条失败 refundNo={}", refund.getRefundNo(), e);
            }
        }
        if (advanced > 0) {
            log.info("退款状态主动查询兜底本轮推进 {} 条", advanced);
        }
        return advanced;
    }
}