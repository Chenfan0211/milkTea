package com.wuling.finance.port;

/**
 * 结算卡端口（易宝收款账户）。
 *
 * <p>server 是结算卡数据的属主，trade-service 只在出款时接收
 * 「子商户号 + 结算卡 ID」两个不透明标识，不认识也不存储银行卡信息。
 * 这样做的目的：减少持卡人敏感信息的可见面。
 *
 * <p><b>未启用易宝时</b>：由 {@code NoopPayoutAccountPort} 提供空实现，
 * 所有方法返回 null，提现走原有微信链路不受影响。
 */
public interface PayoutAccountPort {

    /** 默认结算卡记录 id；无默认卡返回 null。 */
    Long defaultAccountId(Long subjectId);

    /** 默认结算卡对应的易宝结算卡 ID（settle-card/add 返回）；无卡返回 null。 */
    String yeepaySettleCardId(Long subjectId);

    /** 主体对应的易宝子商户编号；未入驻返回 null。 */
    String yeepayMerchantNo(Long subjectId);

    /**
     * 收款账户快照（JSON）。
     *
     * <p>提现申请时固化，写入 {@code withdrawal.payee_snapshot}。
     * 之后经营者换卡不影响历史提现单的凭证。
     */
    String snapshot(Long subjectId);
}
