package com.wuling.finance.port;

/**
 * 出款端口（提现 → 微信商家转账 / 易宝自助结算）。
 *
 * <p>提现出款的真实动作（发起转账/结算）在 trade-service（承载三方 SDK），
 * server 通过本端口委托调用，并根据返回的受理结果收敛提现状态。
 *
 * <p><b>为什么带 {@link Payee}</b>：微信要 openid，易宝要子商户号 + 结算卡 ID。
 * 收款方信息由 server 统一裁决后下发（server 是 subject / 结算卡的属主），
 * trade-service 不再自行推断，避免两个服务对「谁是收款人」理解不一致。
 *
 * <p><b>fail-closed</b>：调用失败 / 受理失败必须返回明确失败信号，
 * 由调用方解冻金额，绝不静默置成功。
 */
public interface PayoutPort {

    /**
     * 发起提现出款（受理）。
     *
     * @param withdrawNo 提现单号（三方出款单号，保证幂等）
     * @param amountFen  金额（分）
     * @param payee      收款方（见 {@link Payee}）
     * @param remark     转账备注
     * @return 受理结果；网络异常等返回 accepted=false
     */
    PayoutResult transfer(String withdrawNo, long amountFen, Payee payee, String remark);

    /**
     * 主动查询出款结果（补偿回调丢失）。
     *
     * <p><b>为什么需要 merchantNo</b>：易宝查询结算记录（{@code settle/records/query}）
     * 必须同时指定商户编号与结算请求号，只有提现单号查不到。
     * server 是结算卡与入驻数据的属主，由它下发 merchantNo 比让
     * trade-service 反查更可靠（也避免 trade 反向依赖 finance 的表）。
     *
     * @param withdrawNo       提现单号（= 三方结算请求号）
     * @param yeepayMerchantNo 易宝子商户编号；微信通道传 null 即可
     * @return 查询结果；通道不支持或查询失败时返回 null
     */
    PayoutQueryResult query(String withdrawNo, String yeepayMerchantNo);

    /**
     * 收款方。
     *
     * @param channel          WXPAY / YEEPAY
     * @param userId           申请人（微信通道据此查 openid）
     * @param subjectId        收款主体（易宝通道据此定位子商户）
     * @param yeepayMerchantNo 易宝子商户编号
     * @param settleCardId     易宝结算卡 ID
     */
    record Payee(String channel, Long userId, Long subjectId,
                 String yeepayMerchantNo, String settleCardId) {

        public static Payee ofWx(Long userId, Long subjectId) {
            return new Payee("WXPAY", userId, subjectId, null, null);
        }

        public static Payee ofYeepay(Long subjectId, String merchantNo, String settleCardId) {
            return new Payee("YEEPAY", null, subjectId, merchantNo, settleCardId);
        }
    }

    /** 出款受理结果。 */
    record PayoutResult(boolean accepted, boolean immediatePaid, String batchNo, String failReason) {
    }

    /** 出款查询结果。status 为三方原始状态（如 SUCCESS / FAIL / PROCESSING）。 */
    record PayoutQueryResult(boolean supported, String status, String failReason) {
    }
}
