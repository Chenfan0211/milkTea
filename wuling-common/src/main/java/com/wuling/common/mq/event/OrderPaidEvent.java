package com.wuling.common.mq.event;

import java.io.Serializable;

/**
 * 订单支付成功事件（第 16 期：邀请奖励发放）。
 *
 * <p><b>由谁发布</b>：trade 服务在订单入账（余额支付 / 微信 / Mock 回调）成功后发布。
 *
 * <p><b>由谁消费</b>：marketing 服务的邀请奖励消费者，用于「好友首单」奖励发放。
 *
 * <p><b>为什么用事件而不是 trade 直接调 marketing 接口</b>：
 * 邀请奖励不是支付的必经环节 —— 发奖失败绝不能影响用户付款。
 * 事件驱动把两者解耦：trade 只管「付成功了」，发奖由 marketing 异步重试/死信兜底。
 *
 * <p><b>为什么不传 payTime</b>：本项目消费端统一使用 {@code new ObjectMapper()}
 * 解析消息体（见 {@code AbstractMqConsumer}），该实例未注册 JavaTimeModule，
 * 携带 LocalDateTime 会反序列化失败。事件只保留发奖必需的标量字段。
 *
 * <p><b>字段约束</b>：只增不改 —— 消费端忽略未知字段，保证历史消息可重放。
 */
public class OrderPaidEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单 ID */
    private Long orderId;
    /** 订单号 */
    private String orderNo;
    /** 下单用户 ID（可能是被邀请人） */
    private Long userId;
    /** 实付金额（分） */
    private Long paidAmount;
    /** 支付渠道标识（STORED_VALUE / WXPAY / MOCK 等，仅用于日志追溯） */
    private String channel;

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(Long paidAmount) {
        this.paidAmount = paidAmount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }
}
