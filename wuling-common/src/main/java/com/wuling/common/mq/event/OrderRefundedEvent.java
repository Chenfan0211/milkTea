package com.wuling.common.mq.event;

import java.io.Serializable;

/**
 * 退款冲正事件（第 6 期）。
 *
 * <p>由 trade 服务在退款成功后发布，finance 服务消费并冲正待结算台账。
 *
 * <p><b>重要</b>：退款侧的「订单是否已结算」前置校验**不走事件**，
 * 而是由 trade 同步查询结算状态（否则存在资金穿透窗口）。
 * 本事件只负责「冲正」这一动作。
 */
public class OrderRefundedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号 */
    private String orderNo;
    /** 订单 ID */
    private Long orderId;
    /** 退款金额（分） */
    private Long refundAmount;
    /** 退款单号 */
    private String refundNo;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(Long refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }
}
