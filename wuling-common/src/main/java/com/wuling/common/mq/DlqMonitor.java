package com.wuling.common.mq;

import com.wuling.common.alert.AlertChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 死信队列监控（第 10 期）。
 *
 * <p>为什么需要：业务队列消费失败重试超限后会 nack(requeue=false) 转 DLQ。
 * DLQ 有消息就意味着「业务最终处理失败」—— 而这几条链路正是资金相关
 * （分账 / 冲正 / 兑换核销）。若不监控，消息会一直躺在 DLQ 里无人问津，
 * 只能靠对账（小时级）事后倒推，且无法得知失败原因。
 *
 * <p><b>比日志告警更早发现问题</b>：DLQ 堆积是「消费失败」的即时信号，
 * 而对账是「账目不一致」的事后验证。前者能提前暴露问题，便于在资金受影响前介入。
 *
 * <p>实现方式：通过 {@link AmqpAdmin#getQueueInfo} 查询各 DLQ 的消息数，
 * 超过阈值则告警。使用 declare=false 查询，不修改队列。
 *
 * <p>告警去重：同一队列连续多轮超阈值只在「首次」告警，
 * 避免每轮都刷告警。恢复（回落到阈值内）时发一条 INFO 便于确认。
 */
/**
 * 注意：需要 RabbitMQ 与 @EnableScheduling。
 * 通过 @ConditionalOnClass 保证在缺少 amqp 依赖时不加载（避免服务启动失败）。
 */
@Component
@ConditionalOnClass(AmqpAdmin.class)
public class DlqMonitor {

    private static final Logger log = LoggerFactory.getLogger(DlqMonitor.class);

    /** 被监控的 DLQ 列表（与 MqConstants 中的定义保持一致） */
    private static final List<String> MONITORED = List.of(
            MqConstants.ORDER_TIMEOUT_DLQ,
            MqConstants.PAYMENT_SUCCESS_DLQ,
            MqConstants.SETTLEMENT_NOTIFY_DLQ,
            MqConstants.FINANCE_SPLIT_DLQ,
            MqConstants.FINANCE_REVERSE_DLQ,
            MqConstants.EXCHANGE_VERIFY_DLQ
    );

    private final AmqpAdmin amqpAdmin;
    private final AlertChannel alertChannel;
    private final boolean enabled;
    private final int threshold;

    /** 上一轮处于告警状态的队列（用于去重） */
    private final List<String> alertingQueues = new ArrayList<>();

    public DlqMonitor(AmqpAdmin amqpAdmin,
                      AlertChannel alertChannel,
                      @Value("${app.mq.dlq-monitor.enabled:true}") boolean enabled,
                      @Value("${app.mq.dlq-monitor.threshold:1}") int threshold) {
        this.amqpAdmin = amqpAdmin;
        this.alertChannel = alertChannel;
        this.enabled = enabled;
        this.threshold = threshold;
    }

    /**
     * 每 5 分钟检查一次 DLQ 堆积。
     *
     * <p>频率高于对账（小时级）—— 因为 DLQ 是即时信号，
     * 早发现可以避免不一致扩散（如分账未执行期间又被结算）。
     */
    @Scheduled(cron = "${app.mq.dlq-monitor.cron:0 */5 * * * ?}")
    public void checkDlq() {
        if (!enabled) {
            return;
        }
        try {
            List<String> nowAlerting = new ArrayList<>();
            for (String queue : MONITORED) {
                QueueInformation info = amqpAdmin.getQueueInfo(queue);
                if (info == null) {
                    // 队列不存在：说明声明漏了（曾真实发生），这本身就需要告警
                    alertChannel.send(AlertChannel.Level.WARNING,
                            "死信队列不存在", "队列 " + queue + " 未声明，死信消息将被丢弃", queue);
                    continue;
                }
                long count = info.getMessageCount();
                if (count >= threshold) {
                    nowAlerting.add(queue);
                    if (!alertingQueues.contains(queue)) {
                        // 首次超阈值才告警
                        alertChannel.send(AlertChannel.Level.CRITICAL,
                                "死信队列堆积",
                                "队列 " + queue + " 有 " + count + " 条死信，"
                                        + "说明消费重试超限，需人工核查失败原因并补偿",
                                queue);
                    }
                } else if (alertingQueues.contains(queue)) {
                    // 从告警恢复
                    alertChannel.send(AlertChannel.Level.INFO,
                            "死信队列已清空", "队列 " + queue + " 已回落到 " + count + " 条", queue);
                }
            }
            alertingQueues.clear();
            alertingQueues.addAll(nowAlerting);
        } catch (Exception e) {
            // 监控失败不影响业务
            log.warn("DLQ 监控执行失败: {}", e.getMessage());
        }
    }
}
