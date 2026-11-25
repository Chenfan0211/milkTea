package com.wuling.finance.payee;

/**
 * 易宝结算卡端口。
 *
 * <p>易宝接口：{@code /rest/v1.0/settle/settle-card/add}。
 * 与「提现卡」（{@code account/withdraw/card/bind}）是两个不同产品，
 * 订单分账路线下只需要结算卡。
 *
 * <p><b>绑定即校验</b>：易宝在绑卡时做卡户名一致性校验，
 * 返回 {@code UA40021 身份证号与银行账号不匹配} 说明开户名与签约主体不符。
 * 因此不需要额外调四要素认证接口（按次计费）。
 */
public interface YeepaySettleCardPort {

    /**
     * 绑定结算卡。
     *
     * @param merchantNo  易宝子商户编号
     * @param accountType PUBLIC / PRIVATE
     * @param cardNo      银行卡号（明文，内网传输）
     * @param bankCode    开户总行编码
     * @param branchCode  开户支行编码（农信社/小额农行建议填）
     */
    BindResult bind(String merchantNo, String accountType, String cardNo,
                    String bankCode, String branchCode);

    /**
     * 绑定结果。
     *
     * @param accepted     是否成功
     * @param settleCardId 易宝结算卡 ID（成功后返回）
     * @param failReason   失败原因（易宝错误描述）
     */
    record BindResult(boolean accepted, String settleCardId, String failReason) {
    }
}
