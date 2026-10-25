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

    /** 消费失败后的延迟重试交换机 */
    public static final String RETRY_EXCHANGE = "wuling.retry.exchange";

    /** 消费失败后的延迟重试队列，消息 TTL 到期后回到业务交换机 */
    public static final String RETRY_QUEUE = "wuling.retry.queue";

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

    /**
      优惠券状态事件（trade 发布，marketing 消费）。

      payload 至少包含 action、userId、userCouponId、orderNo，payload 类型见 CouponEvent。
      消费者按 action 调用既有 CouponService 幂等状态流转，资金与订单主链不通过 MQ 变更。
    */
    public static final String COUPON_EVENT_QUEUE = "wuling.coupon.event.queue";
    public static final String COUPON_EVENT_ROUTING_KEY = "wuling.coupon.event";

    /**
      设备消息队列（茶机对接）。

      场景：阿里云 MNS（轻量消息队列）推送的「设备状态 / 胶囊冲泡记录」，
      由常驻消费端拉取后【转投本队列】，再由 DeviceMessageConsumer 落库。

      为什么不让 MNS 消费端直接写库：转投可复用现有幂等（MqIdempotent）、
      延迟重试与死信队列基建，第三方消息解析/网络抖动不会直接丢数据。

      一致性说明：MNS 侧以「RabbitMQ 发布确认成功」为 ack 条件，
      未确认则不删消息，由 MNS 重投（at-least-once）；
      落库侧再用业务唯一键幂等兜底，重复消息不会产生重复记录。
    */
    public static final String DEVICE_MESSAGE_QUEUE = "wuling.device.message.queue";
    public static final String DEVICE_MESSAGE_ROUTING_KEY = "wuling.device.message";

    /** 下单成功订阅消息：trade 消费。 */
    public static final String SUBSCRIBE_ORDER_QUEUE = "wuling.subscribe.order.queue";
    public static final String SUBSCRIBE_ORDER_ROUTING_KEY = "wuling.subscribe.order";

    /** 营销订阅消息：marketing 消费。 */
    public static final String SUBSCRIBE_MARKETING_QUEUE = "wuling.subscribe.marketing.queue";
    public static final String SUBSCRIBE_MARKETING_ROUTING_KEY = "wuling.subscribe.marketing";

    // ---------- 死信队列（每个业务队列一个 DLQ） ----------

    public static final String ORDER_TIMEOUT_DLQ = ORDER_TIMEOUT_QUEUE + ".dlq";
    public static final String PAYMENT_SUCCESS_DLQ = PAYMENT_SUCCESS_QUEUE + ".dlq";
    public static final String SETTLEMENT_NOTIFY_DLQ = SETTLEMENT_NOTIFY_QUEUE + ".dlq";
    public static final String EXCHANGE_VERIFY_DLQ = EXCHANGE_VERIFY_QUEUE + ".dlq";
    public static final String FINANCE_SPLIT_DLQ = FINANCE_SPLIT_QUEUE + ".dlq";
    public static final String FINANCE_REVERSE_DLQ = FINANCE_REVERSE_QUEUE + ".dlq";
    public static final String COUPON_EVENT_DLQ = COUPON_EVENT_QUEUE + ".dlq";
    public static final String DEVICE_MESSAGE_DLQ = DEVICE_MESSAGE_QUEUE + ".dlq";
    public static final String SUBSCRIBE_ORDER_DLQ = SUBSCRIBE_ORDER_QUEUE + ".dlq";
    public static final String SUBSCRIBE_MARKETING_DLQ = SUBSCRIBE_MARKETING_QUEUE + ".dlq";

    /** 消息重试头（记录已重试次数，由消费者更新） */
    public static final String HEADER_RETRY_COUNT = "x-retry-count";

    /** 消息唯一 ID 头（用于幂等去重） */
    public static final String HEADER_MESSAGE_ID = "x-message-id";

    /** 业务追踪键头 */
    public static final String HEADER_BIZ_KEY = "biz-key";
}
