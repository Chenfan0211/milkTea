package com.wuling.trade.pay.storedvalue;

/**
 * 储值订单查询/流转端口（第 15 期储值支付接入）。
 *
 * <p><b>为什么需要它</b>：储值订单落在 marketing 域（{@code stored_value_order}），
 * 而支付单与微信支付网关在 trade 域（{@code payment}）。
 * 微信回调只带回 {@code out_trade_no}，trade 域必须能反查储值订单并驱动入账，
 * 否则会出现「用户已付款、余额永不入账」的资损场景。
 *
 * <p>走端口而非直连 marketing 库：两服务已是独立部署单元，
 * 共享库会让任一侧的表结构调整同时波及对方。
 *
 * <p><b>失败语义</b>：资金相关查询失败<b>必须抛异常</b>，
 * 不能静默返回 null 后当作「无此订单」放过 —— 那会让回调静默失败，
 * 用户以为没付成功而重复支付。
 */
public interface StoredValueOrderPort {

    /** 储值订单视图（trade 域只读所需字段） */
    class StoredValueOrderView {
        private String orderNo;
        private Long userId;
        private Long amount;
        private String payStatus;

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

        public Long getAmount() {
            return amount;
        }

        public void setAmount(Long amount) {
            this.amount = amount;
        }

        public String getPayStatus() {
            return payStatus;
        }

        public void setPayStatus(String payStatus) {
            this.payStatus = payStatus;
        }
    }

    /**
     * 按订单号查询储值订单。
     *
     * @param orderNo 储值订单号（CZ 前缀）
     * @return 订单视图；确实不存在时返回 null
     */
    StoredValueOrderView findByOrderNo(String orderNo);

    /**
     * 驱动储值订单入账（幂等）。
     *
     * <p>由支付回调调用。marketing 侧以「UNPAID → PAID」条件更新作为幂等闸门，
     * 重复调用不会重复入账。
     *
     * @param orderNo       储值订单号
     * @param transactionId 微信支付交易号
     * @param payerOpenid   支付者 openid（退款原路退回必需）
     * @param callbackAmount 回调金额（分），用于服务端二次比对
     */
    void markPaid(String orderNo, String transactionId, String payerOpenid, Long callbackAmount);
}
