package com.wuling.finance.port;

/**
 * 出款端口（提现 → 微信商家转账）。
 *
 * <p>提现出款的真实动作（发起转账）在 trade-service（承载微信 SDK），
 * server 通过本端口委托调用，并根据返回的受理结果收敛提现状态。
 *
 * <p><b>fail-closed</b>：调用失败 / 受理失败必须返回明确失败信号，
 * 由调用方解冻金额，绝不静默置成功。
 */
public interface PayoutPort {

    /**
     * 发起提现出款（受理）。
     *
     * @param withdrawNo 提现单号（作为微信 out_detail_no，保证幂等）
     * @param amountFen  金额（分）
     * @param userId     申请人（trade-service 据此查 openid）
     * @param remark     转账备注
     * @return 受理结果；网络异常等返回 accepted=false
     */
    PayoutResult transfer(String withdrawNo, long amountFen, Long userId, String remark);

    /** 出款受理结果。 */
    record PayoutResult(boolean accepted, boolean immediatePaid, String batchNo, String failReason) {
    }
}
