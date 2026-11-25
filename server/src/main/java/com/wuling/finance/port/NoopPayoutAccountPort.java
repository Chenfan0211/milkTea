package com.wuling.finance.port;

/**
 * 结算卡端口的空实现（未启用易宝通道时使用）。
 *
 * <p><b>为什么需要它</b>：{@code WithdrawalService} 无条件注入本端口，
 * 但易宝未必启用（默认就是关闭的）。若直接依赖真实实现（需要数据库表），
 * 未建表的环境会启动失败。空实现让微信链路完全不受易宝存在与否影响。
 *
 * <p>语义：一律返回 null，等价于「没有易宝收款账户」。
 * 由于未启用易宝时提现不会走易宝分支，这些 null 不会被用到。
 */
public class NoopPayoutAccountPort implements PayoutAccountPort {

    @Override
    public Long defaultAccountId(Long subjectId) {
        return null;
    }

    @Override
    public String yeepaySettleCardId(Long subjectId) {
        return null;
    }

    @Override
    public String yeepayMerchantNo(Long subjectId) {
        return null;
    }

    @Override
    public String snapshot(Long subjectId) {
        return null;
    }
}
