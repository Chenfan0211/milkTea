package com.wuling.trade.pay.payout;

import org.springframework.stereotype.Component;

/**
 * Mock 出款通道：未配置商户号时使用，维持「提现同步到账」的记账行为。
 *
 * <p><b>与支付侧 MockPaymentGateway 的区别</b>：支付 mock 不做真实资金流，
 * 但提现 mock 需要「立即成功」以维持现有记账语义（申请冻结后即时到账）。
 * 因此 {@link #transfer} 直接返回 accepted=true，{@link #query} 返回 SUCCESS。
 *
 * <p><b>为什么查询返回 SUCCESS 而非 null</b>：提现的 mock 语义是「同步成功」，
 * 若查询返回 null 会让调用方把成功的单误判为「状态不明」而解冻，破坏现有行为。
 * 与支付「不伪造成功」的区别在于：支付 mock 无三方交易，提现 mock 的「成功」就是业务成功。
 */
@Component
public class MockPayoutGateway implements PayoutGateway {

    @Override
    public String channel() {
        return "MOCK";
    }

    @Override
    public PayoutResult transfer(String outDetailNo, long amountFen, String openid, String remark) {
        return new PayoutResult(true, "MOCK-BATCH-" + System.currentTimeMillis(), null);
    }

    @Override
    public boolean isImmediate() {
        return true;
    }

    @Override
    public PayoutQueryResult query(String outDetailNo) {
        return new PayoutQueryResult("SUCCESS", null);
    }
}
