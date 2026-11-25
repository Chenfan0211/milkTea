package com.wuling.finance.divide;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 分账失败重试任务。
 *
 * <p><b>为什么必须有它</b>：分账失败（网络抖动、易宝限流 OPR21006、
 * 收款方当时未入驻）此前只能靠「入驻完成」事件触发补分。
 * 若入驻早已完成、只是那次调用失败，这笔钱会永久留在平台待分账账户，
 * 经营者看得到余额却永远提不出来。
 *
 * <p><b>为什么不在提现时兜底</b>：提现走的是「一次自助结算」，
 * 不逐笔分账（逐笔分几十单要几分钟）。分账必须由本任务与核销链路负责。
 *
 * <p><b>幂等由 {@code divide_status} 保证</b>：只处理 FAIL 与 PENDING_ONBOARD，
 * 已 SUCCESS 的不会重复分；PROCESSING 的也刻意跳过（结果未知，重发会重复打钱）。
 */
@Component
public class DivideRetryJob {

    private static final Logger log = LoggerFactory.getLogger(DivideRetryJob.class);

    /** 单轮最多处理的结算记录数。 */
    private static final int BATCH_LIMIT = 200;

    /** 「已分账未绑卡」条数达到该阈值时升级为 ERROR 告警。 */
    private static final long STUCK_ALERT_THRESHOLD = 10L;

    private final OrderDivideService orderDivideService;
    private final boolean yeepayEnabled;

    public DivideRetryJob(OrderDivideService orderDivideService,
                          @Value("${app.pay.yeepay.channel:}") String yeepayChannel) {
        this.orderDivideService = orderDivideService;
        this.yeepayEnabled = "yeepay".equalsIgnoreCase(
                yeepayChannel == null ? "" : yeepayChannel.trim());
    }

    /**
     * 每 2 小时重试一次。
     *
     * <p>为什么不用更高频：分账失败多为「未入驻」或「配置问题」，
     * 这类原因不会在几分钟内自愈；高频重试只会刷日志并触发易宝限流。
     */
    @Scheduled(cron = "${app.divide-retry.cron:0 15 */2 * * ?}")
    public void retryPendingDivides() {
        if (!yeepayEnabled) {
            return;
        }
        try {
            int attempted = orderDivideService.retryPendingDivides(BATCH_LIMIT);
            if (attempted > 0) {
                log.info("分账重试任务处理 {} 笔订单", attempted);
            }
            reportStuckDivides();
        } catch (Exception e) {
            // 定时任务异常不能抛：抛出去会被调度器吞掉且看不到原因
            log.error("分账重试任务异常 err={}", e.getMessage(), e);
        }
    }

    /**
     * 统计「已分账成功但收款方未绑结算卡」的记录数。
     *
     * <p><b>为什么要报这个</b>：按产品决定「入驻完成即分账」，
     * 未绑卡时钱会进子商户待结算账户。经营者能看到余额却提不出来，
     * 而日志里的单条 warn 容易被淹没——汇总成一条告警才能让运营主动催办。
     *
     * <p>超过阈值打 ERROR（而非 WARN），便于告警通道按级别过滤。
     */
    private void reportStuckDivides() {
        try {
            long stuck = orderDivideService.countDividedWithoutSettleCard();
            if (stuck <= 0) {
                return;
            }
            if (stuck >= STUCK_ALERT_THRESHOLD) {
                log.error("有 {} 条已分账记录对应的经营者未绑定结算卡，"
                        + "资金滞留在子商户待结算账户，请运营催办绑卡", stuck);
            } else {
                log.warn("有 {} 条已分账记录对应的经营者未绑定结算卡（未达告警阈值 {}）",
                        stuck, STUCK_ALERT_THRESHOLD);
            }
        } catch (Exception e) {
            log.warn("统计滞留在子商户的分账失败 err={}", e.getMessage());
        }
    }
}
