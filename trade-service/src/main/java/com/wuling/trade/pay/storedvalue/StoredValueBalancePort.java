package com.wuling.trade.pay.storedvalue;

/**
 * 储值余额资金操作端口。
 *
 * <p><b>为什么与 {@link StoredValueOrderPort} 分开</b>：那个端口管「充值订单」
 * （CZ 单的生命周期），本端口管「余额这笔钱本身」的进出。两者业务含义不同：
 * <ul>
 *   <li>充值入账 —— 由支付回调驱动，幂等靠 UNPAID→PAID；</li>
 *   <li>余额支付 / 退回 —— 由订单链路同步驱动，必须是同步返回结果
 *       （余额不足要立刻拒绝下单，不能异步）。</li>
 * </ul>
 *
 * <p><b>失败语义</b>：余额不足是<b>正常业务分支</b>，通过返回值表达，
 * 不抛异常（抛异常会让订单侧无法区分「余额不足」与「服务不可用」）；
 * 而网络/服务异常则抛异常，让订单事务回滚。
 */
public interface StoredValueBalancePort {

    /**
     * 余额支付扣款结果。
     *
     * @param success 是否扣款成功
     * @param message 失败原因（余额不足等），成功时为 null
     */
    record DeductResult(boolean success, String message) {

        public static DeductResult ok() {
            return new DeductResult(true, null);
        }

        public static DeductResult fail(String message) {
            return new DeductResult(false, message);
        }
    }

    /**
     * 从储值余额扣款（点单订单的余额支付）。
     *
     * <p>服务端原子扣减并保证不会扣成负数；余额不足时返回
     * {@code success=false}，由调用方回滚订单事务并向用户提示。
     *
     * @param userId 用户 ID
     * @param amount 金额（分），必须 &gt; 0
     * @param bizNo  业务单号（点单订单号），用于对账追溯
     * @return 扣款结果；<b>服务异常时抛异常</b>
     */
    DeductResult deduct(Long userId, long amount, String bizNo);

    /**
     * 把余额支付的金额原路退回储值余额。
     *
     * <p><b>仅用于「余额支付的订单」退款/取消</b>：
     * 当初用余额付的，退回余额；储值充值本身不可退。
     *
     * @param userId 用户 ID
     * @param amount 金额（分），必须 &gt; 0
     * @param bizNo  业务单号（点单订单号），用于对账追溯
     */
    void refund(Long userId, long amount, String bizNo);
}