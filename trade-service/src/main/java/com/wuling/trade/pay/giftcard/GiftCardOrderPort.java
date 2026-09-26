package com.wuling.trade.pay.giftcard;

/**
 * 礼品卡订单支付/退款编排端口。
 *
 * <p>礼品卡订单属于 marketing 域，微信支付单属于 trade 域。trade 仅通过该端口
 * 查询订单并驱动状态机，不直连 marketing 数据库。
 *
 * <p>所有方法涉及资金，远程失败必须抛异常，不能静默忽略。
 */
public interface GiftCardOrderPort {

    /** trade 支付/退款编排所需的礼品卡订单视图。 */
    class GiftCardOrderView {
        private String orderNo;
        private Long userId;
        private Long amount;
        private String payStatus;
        private String status;
        private String verifyStatus;
        private String refundStatus;
        private String transactionId;
        private java.time.LocalDateTime expireTime;

        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        public String getPayStatus() { return payStatus; }
        public void setPayStatus(String payStatus) { this.payStatus = payStatus; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getVerifyStatus() { return verifyStatus; }
        public void setVerifyStatus(String verifyStatus) { this.verifyStatus = verifyStatus; }
        public String getRefundStatus() { return refundStatus; }
        public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        public java.time.LocalDateTime getExpireTime() { return expireTime; }
        public void setExpireTime(java.time.LocalDateTime expireTime) { this.expireTime = expireTime; }
    }

    /** 查询礼品卡订单；不存在时返回 null。 */
    GiftCardOrderView findByOrderNo(String orderNo);

    /** 支付成功入账并向订单发卡，marketing 侧保证幂等。 */
    void settle(String orderNo, String transactionId, String payerOpenid, Long callbackAmount);

    /** 创建退款单并把订单置为退款中。 */
    GiftCardRefundResult refundBegin(String orderNo, String reason);

    /** 微信退款终态成功。 */
    void refundConfirm(String orderNo, String refundNo, String wxRefundId);

    /** 微信退款失败或同步下单失败。 */
    void refundFail(String orderNo, String refundNo, String failReason);
}
