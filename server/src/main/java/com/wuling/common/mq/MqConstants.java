package com.wuling.common.mq;

/**
 * 消息队列常量。
 *
 * 命名规范：
 * - 交换机：wuling.{业务}.exchange
 * - 业务队列：wuling.{业务}.queue
 * - 路由键：wuling.{业务}.{动作}
 * - 死信队列：wuling.{业务}.queue.dlq（由基础设施统一生成）
 *
 * 设计说明：
 * 所有业务队列都配置了死信，消费失败超过重试次数后自动转入对应 DLQ，
 * 避免坏消息无限重试阻塞队列。
 */
public final class MqConstants {

    private MqConstants() {
    }

    /** 默认业务交换机（直连型） */
    public static final String BUSINESS_EXCHANGE = "wuling.business.exchange";

    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "wuling.dlx.exchange";

    /** 延迟交换机（用于订单超时等延迟场景） */
    public static final String DELAY_EXCHANGE = "wuling.delay.exchange";

    // ---------- 业务队列 ----------

    /** 订单超时关闭队列（延迟 15 分钟） */
    public static final String ORDER_TIMEOUT_QUEUE = "wuling.order.timeout.queue";
    public static final String ORDER_TIMEOUT_ROUTING_KEY = "wuling.order.timeout";

    /** 支付成功后的异步处理（分账、通知） */
    public static final String PAYMENT_SUCCESS_QUEUE = "wuling.payment.success.queue";
    public static final String PAYMENT_SUCCESS_ROUTING_KEY = "wuling.payment.success";

    /** 结算完成通知 */
    public static final String SETTLEMENT_NOTIFY_QUEUE = "wuling.settlement.notify.queue";
    public static final String SETTLEMENT_NOTIFY_ROUTING_KEY = "wuling.settlement.notify";

    // ---------- 死信队列（每个业务队列一个 DLQ） ----------

    public static final String ORDER_TIMEOUT_DLQ = ORDER_TIMEOUT_QUEUE + ".dlq";
    public static final String PAYMENT_SUCCESS_DLQ = PAYMENT_SUCCESS_QUEUE + ".dlq";
    public static final String SETTLEMENT_NOTIFY_DLQ = SETTLEMENT_NOTIFY_QUEUE + ".dlq";

    /** 消息重试头（记录已重试次数，由消费者更新） */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";

    /** 消息唯一 ID 头（用于幂等去重） */
    public static final String HEADER_MESSAGE_ID = "x-message-id";
}
