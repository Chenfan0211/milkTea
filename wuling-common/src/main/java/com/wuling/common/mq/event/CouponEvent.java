package com.wuling.common.mq.event;

import java.io.Serializable;

/**
 * 优惠券状态事件（trade 发布，marketing 消费）。
 *
 * <p>事件只负责优惠券状态流转，不承载余额扣款等资金动作。所有 action 至少携带
 * userId、userCouponId、orderNo；RELEASE 额外可携带 reason，eventId 可选。</p>
 *
 * <p>字段语义保持向后兼容：新增字段只能追加，不能改变已有字段含义。</p>
 */
public class CouponEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 核销优惠券。 */
    public static final String ACTION_CONSUME = "CONSUME";
    /** 未支付取消或超时后释放锁券。 */
    public static final String ACTION_RELEASE = "RELEASE";
    /** 支付后退款恢复优惠券。 */
    public static final String ACTION_RESTORE_REFUND = "RESTORE_REFUND";

    /** 幂等事件 ID；未由生产者显式提供时，消费端会按业务字段生成稳定键。 */
    private String eventId;
    /** 状态流转动作，取值见本类 ACTION_* 常量。 */
    private String action;
    /** 用户 ID。 */
    private Long userId;
    /** 用户优惠券 ID。 */
    private Long userCouponId;
    /** 订单号。 */
    private String orderNo;
    /** RELEASE 可选的释放原因。 */
    private String reason;

    public CouponEvent() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getUserCouponId() {
        return userCouponId;
    }

    public void setUserCouponId(Long userCouponId) {
        this.userCouponId = userCouponId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
