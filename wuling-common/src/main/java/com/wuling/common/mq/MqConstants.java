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

    /**
      兑换核销（第 5 期新增）。

      场景：trade 服务核销兑换码时，兑换单归属 marketing 服务。
      为避免 trade 直接依赖 marketing（跨服务强耦合），
      改为：trade 发布「兑换核销」事件 -> marketing 消费并置兑换单为 VERIFIED。

      一致性说明（重要）：
        这是最终一致模型 —— trade 核销记录先落库，marketing 异步更新兑换单状态。
        若消息丢失，兑换单会停留在待核销状态；
        由于消费带幂等（MqIdempotent），重复投递不会造成重复核销。
        兑换单状态更新对「门店已放行」的判定不构成阻塞（核销记录已落库）。
    */
    public static final String EXCHANGE_VERIFY_QUEUE = "wuling.marketing.exchange-verify.queue";
    public static final String EXCHANGE_VERIFY_ROUTING_KEY = "wuling.marketing.exchange-verify";

    /**
      核销分账（第 6 期新增）。

      场景：trade 核销订单后需执行五方分账（写 split_snapshot + settlement_record），
      而分账逻辑归属 finance 服务。改为事件驱动，避免 trade 直接依赖 finance。

      一致性说明：
        核销记录与订单状态在 trade 侧先落库（门店据此放行，不阻塞）；
        finance 异步执行分账，最终一致；
        executeSplit 自身幂等（同一订单已存在快照则复用），配合 MqIdempotent 双保险。
    */
    public static final String FINANCE_SPLIT_QUEUE = "wuling.finance.split.queue";
    public static final String FINANCE_SPLIT_ROUTING_KEY = "wuling.finance.split";

    /**
      退款冲正（第 6 期新增）。

      注意：退款侧的「已结算则拒绝退款」前置校验必须【同步】查询 settlement_record，
      不能用事件替代（否则存在资金穿透窗口）。本事件只负责冲正动作本身。
    */
    public static final String FINANCE_REVERSE_QUEUE = "wuling.finance.reverse.queue";
    public static final String FINANCE_REVERSE_ROUTING_KEY = "wuling.finance.reverse";

    // ---------- 死信队列（每个业务队列一个 DLQ） ----------

    public static final String ORDER_TIMEOUT_DLQ = ORDER_TIMEOUT_QUEUE + ".dlq";
    public static final String PAYMENT_SUCCESS_DLQ = PAYMENT_SUCCESS_QUEUE + ".dlq";
    public static final String SETTLEMENT_NOTIFY_DLQ = SETTLEMENT_NOTIFY_QUEUE + ".dlq";
    public static final String EXCHANGE_VERIFY_DLQ = EXCHANGE_VERIFY_QUEUE + ".dlq";
    public static final String FINANCE_SPLIT_DLQ = FINANCE_SPLIT_QUEUE + ".dlq";
    public static final String FINANCE_REVERSE_DLQ = FINANCE_REVERSE_QUEUE + ".dlq";

    /** 消息重试头（记录已重试次数，由消费者更新） */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";

    /** 消息唯一 ID 头（用于幂等去重） */
    public static final String HEADER_MESSAGE_ID = "x-message-id";
}
