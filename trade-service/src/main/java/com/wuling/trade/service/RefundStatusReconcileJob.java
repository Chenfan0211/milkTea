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

    /**
     * 退款单超过该分钟数仍未终态才主动查询。
     *
     * <p><b>为什么从 5 分钟降到 1 分钟</b>：本值直接决定「用户点取消后要等多久才变已取消」。
     * 原来 5 分钟 + 5 分钟 cron，最坏情况下用户要盯着「退款中」近 10 分钟；
     * 微信侧退款通常几秒到账，回调若未送达（域名/验签问题），本地就只能等兜底。
     * 降为 1 分钟后与 cron 同频，配合 {@code quickRecheck} 即时重查，感知延迟压到 1 分钟内。
     */
    private static final long DEFAULT_QUERY_AFTER_MINUTES = 1;

    private final RefundMapper refundMapper;
    private final RefundService refundService;
    private final boolean enabled;
    private final int batchSize;
    /** 退款单「多大年纪」才允许被主动查询（分钟）；越小越早接管，但过小会与正常回调重复查询。 */
    @Value("${app.refund-reconcile.query-after-minutes:1}")
    private long queryAfterMinutes = DEFAULT_QUERY_AFTER_MINUTES;

    /**
     * Spring 装配用构造器。
     *
     * <p>{@code queryAfterMinutes} 走字段 {@code @Value} 注入（而非构造器参数）——
     * 它与 {@code batchSize} 同为 {@code int}/{@code long}，若都放进构造器会产生
     * 相同签名的重载而编译失败；字段注入也更便于测试覆写。
     */
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

    @Scheduled(cron = "${app.refund-reconcile.cron:0 */1 * * * ?}")
    public void run() {
        sweep();
    }

    /** 单轮扫描，便于测试与运维手动触发。 */
    public int sweep() {
        if (!enabled) {
            return 0;
        }
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(queryAfterMinutes);
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