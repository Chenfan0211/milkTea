package com.wuling.trade.pay.payout;

/**
 * 出款适配层（提现 → 微信「商家转账到零钱」/ 易宝「分账 + 自助结算」）。
 *
 * <p>与支付侧 {@code PaymentGateway} 对齐的设计：
 * <ul>
 *   <li>{@code mock}（默认）、{@code wxpay}、{@code yeepay} 多个实现共存，由
 *       {@code app.pay.payout-channel} 经 {@code PayoutGatewayResolver} 选型；</li>
 *   <li>转账是<b>异步</b>的：{@link #transfer} 只代表「已受理」，最终到账结果
 *       必须由回调或 {@link #query} 确认；</li>
 *   <li>幂等关键：{@code outTradeNo} 用提现单号（全局唯一），
 *       重复发起/重复回调都不会二次打款。</li>
 * </ul>
 *
 * <p><b>为什么用 {@link PayeeRef} 而不是散装参数</b>：微信要 openid、
 * 易宝要子商户号 + 结算卡 ID，两个通道的收款方标识完全不同。用统一的
 * 收款方引用对象承载，新增通道时不必再改签名，也不会出现「把易宝的卡号
 * 当 openid 传给微信」这类串道事故。
 *
 * <p><b>fail-closed</b>：受理失败、查询失败一律返回失败信号，由调用方
 * 解冻金额，<b>绝不</b>静默置成功。
 */
public interface PayoutGateway {

    /** 渠道标识，如 MOCK / WXPAY / YEEPAY */
    String channel();

    /**
     * 收款方引用。各通道只取自己认识的字段，其余忽略。
     *
     * @param subjectId        收款主体（biz_subject.id），用于易宝子商户映射与审计
     * @param channel          期望的出款通道（MOCK / WXPAY / YEEPAY）；
     *                         与当前生效通道不一致时由调用方拦截，防止跨池出款
     * @param wxOpenid         微信零钱收款人 openid（仅 WXPAY 使用，服务端查得）
     * @param yeepayMerchantNo 易宝子商户编号（仅 YEEPAY 使用）
     * @param settleCardId     易宝结算卡 ID（仅 YEEPAY 使用）
     */
    record PayeeRef(Long subjectId,
                    String channel,
                    String wxOpenid,
                    String yeepayMerchantNo,
                    String settleCardId) {

        /** 微信通道：只需 openid。 */
        public static PayeeRef ofWx(Long subjectId, String openid) {
            return new PayeeRef(subjectId, "WXPAY", openid, null, null);
        }

        /** 易宝通道：子商户号 + 结算卡。 */
        public static PayeeRef ofYeepay(Long subjectId, String merchantNo, String settleCardId) {
            return new PayeeRef(subjectId, "YEEPAY", null, merchantNo, settleCardId);
        }
    }

    /**
     * 发起单笔出款（受理）。
     *
     * @param outTradeNo 商户出款单号（本项目用提现单号 withdraw_no，全局唯一）
     * @param amountFen  金额（分）
     * @param payee      收款方引用
     * @param remark     转账备注（展示给收款人）
     * @return 受理结果；通道不支持时返回 null
     */
    PayoutResult transfer(String outTradeNo, long amountFen, PayeeRef payee, String remark);

    /**
     * 是否「即时到账」通道（无需等回调）。
     *
     * <p>mock 通道返回 true（转账即成功，维持提现同步到账语义）；
     * wxpay / yeepay 返回 false（异步受理，最终结果由回调/查询确认）。
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
     *
     * @param outTradeNo       商户出款单号（提现单号）
     * @param yeepayMerchantNo 易宝子商户编号；易宝查询结算记录必需，微信通道忽略
     */
    PayoutQueryResult query(String outTradeNo, String yeepayMerchantNo);

    /** 转账受理结果。 */
    record PayoutResult(boolean accepted, String batchNo, String failReason) {
    }

    /** 转账查询结果。 */
    record PayoutQueryResult(String status, String failReason) {
    }
}
