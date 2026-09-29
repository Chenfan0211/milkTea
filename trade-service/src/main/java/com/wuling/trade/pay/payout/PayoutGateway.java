package com.wuling.trade.pay.payout;

/**
 * 出款适配层（提现 → 微信「商家转账到零钱」）。
 *
 * <p>与支付侧 {@code PaymentGateway} 对齐的设计：
 * <ul>
 *   <li>{@code mock}（默认）与 {@code wxpay} 两个实现共存，由
 *       {@code app.pay.channel} 经 {@code PayoutGatewayResolver} 选型；</li>
 *   <li>转账是<b>异步</b>的：{@link #transfer} 只代表「已受理」，最终到账结果
 *       必须由回调或 {@link #query} 确认；</li>
 *   <li>幂等关键：{@code outDetailNo} 用提现单号（全局唯一），
 *       重复发起/重复回调都不会二次打款。</li>
 * </ul>
 *
 * <p><b>fail-closed</b>：受理失败、查询失败一律返回失败信号，由调用方
 * 解冻金额，<b>绝不</b>静默置成功。
 */
public interface PayoutGateway {

    /** 渠道标识，如 MOCK / WXPAY */
    String channel();

    /**
     * 发起单笔转账（受理）。
     *
     * @param outDetailNo 商户转账单号（本项目用提现单号 withdraw_no，全局唯一）
     * @param amountFen   金额（分）
     * @param openid      收款人 openid（服务端按 userId 查得，绝不接受前端传入）
     * @param remark      转账备注（展示给收款人）
     * @return 受理结果；通道不支持转账时返回 null
     */
    PayoutResult transfer(String outDetailNo, long amountFen, String openid, String remark);

    /**
     * 是否「即时到账」通道（无需等回调）。
     *
     * <p>mock 通道返回 true（转账即成功，维持提现同步到账语义）；
     * wxpay 返回 false（异步受理，最终结果由回调/查询确认）。
     * 调用方据此决定「直接置 PAID」还是「置 PROCESSING 等回调」。
     */
    default boolean isImmediate() {
        return false;
    }

    /**
     * 主动查询转账结果（补偿回调丢失）。
     *
     * <p>失败语义：查不到 / 通道不支持返回 {@code null}，由调用方决定
     * 「保持 PROCESSING 待重试」或「标记失败解冻」。刻意不抛异常，
     * 因为「查不到」是补偿流程的正常分支。
     */
    PayoutQueryResult query(String outDetailNo);

    /** 转账受理结果。 */
    record PayoutResult(boolean accepted, String batchNo, String failReason) {
    }

    /** 转账查询结果。 */
    record PayoutQueryResult(String status, String failReason) {
    }
}
