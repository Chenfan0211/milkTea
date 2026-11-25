package com.wuling.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.finance.entity.SettlementRecord;
import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.SettlementRecordMapper;
import com.wuling.finance.mapper.SubjectAccountMapper;
import com.wuling.finance.mapper.WithdrawalMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 渠道可提现额计算（只读，不落表）。
 *
 * <p><b>为什么需要它</b>：余额池不分渠道（{@code subject_account} 只有
 * available / frozen / deposit 三个桶），但资金实际分散在两个资金池：
 * 微信收的钱在微信商户号，易宝收的钱在经营者子商户待结算账户。
 *
 * <p>若不做渠道校验，一个主体「易宝只剩 500 元」时提 1500 元，
 * 易宝侧结算必然失败（余额不足），钱只能从微信池垫付——这是真正的资损。
 *
 * <p><b>算法</b>：<pre>
 *   易宝可提现额 = Σ 该主体在易宝渠道已分账成功的结算记录
 *                - 已退款/已冲正部分
 *                - 已发起但未完成的易宝提现
 * </pre>
 * 只统计 {@code divide_status = SUCCESS} 的记录，因为只有真正分到子商户的钱
 * 才能从子商户结算出来。{@code PENDING_ONBOARD}（未入驻尚未分账）的钱
 * 还在平台待分账账户，不能计入可提现额。
 */
@Service
public class ChannelBalanceService {

    private static final Logger log = LoggerFactory.getLogger(ChannelBalanceService.class);

    private final SettlementRecordMapper settlementRecordMapper;
    private final WithdrawalMapper withdrawalMapper;
    private final SubjectAccountMapper subjectAccountMapper;

    public ChannelBalanceService(SettlementRecordMapper settlementRecordMapper,
                                 WithdrawalMapper withdrawalMapper,
                                 SubjectAccountMapper subjectAccountMapper) {
        this.settlementRecordMapper = settlementRecordMapper;
        this.withdrawalMapper = withdrawalMapper;
        this.subjectAccountMapper = subjectAccountMapper;
    }

    /**
     * 该主体在易宝渠道的可提现额（分）。
     *
     * <p>已分账成功的结算记录 − 已发起的易宝提现（含处理中与已到账）。
     * 提现成功时结算记录并不会被删除，所以必须扣减在途与已完成的提现，
     * 否则同一笔钱可以重复提。
     */
    public long yeepayWithdrawable(Long subjectId) {
        if (subjectId == null) {
            return 0L;
        }
        long divided = sumDividedSettled(subjectId);
        long withdrawnOrFrozen = sumYeepayWithdrawals(subjectId);
        long available = divided - withdrawnOrFrozen;
        if (available < 0) {
            // 理论上不该出现；出现说明账实不符，记 warn 供运营核查，但不阻断提现流程
            log.warn("易宝渠道可提现额为负，需核查 subjectId={} divided={} withdrawn={}",
                    subjectId, divided, withdrawnOrFrozen);
            return 0L;
        }
        return available;
    }

    /**
     * 该主体在微信渠道的可提现额（分）。
     *
     * <p><b>算法</b>：总可用余额 − 易宝渠道尚可提的部分。
     *
     * <p><b>关键前提（决定此处不能简化）</b>：提现申请时 {@code FREEZE}
     * 立即把 available 转入 frozen，所以 {@code available_balance}
     * 已扣除所有在途与已完成的提现。<b>但</b>{@code sumDividedSettled}
     * 统计的是「已分到易宝子商户」的金额，这部分在本地账上仍记为收入
     * （易宝的钱由平台代收），尚未从 available 扣除。
     *
     * <p>逐场景验证（总收入 1000：易宝 600、微信 400）：
     * <pre>
     * 场景 A：易宝已分账、未提现
     *   available = 1000, yeepayWithdrawable = 600 − 0 = 600
     *   wx = 1000 − 600 = 400   ✓
     *
     * 场景 B：易宝已分账、提现 600 已成功（WITHDRAW 扣 frozen）
     *   available = 400,  yeepayWithdrawable = 600 − 600 = 0
     *   wx = 400 − 0 = 400      ✓
     *
     * 场景 C：易宝已分账、提现 600 被驳回（UNFREEZE 回 available）
     *   available = 1000, yeepayWithdrawable = 600 − 0 = 600（FAILED 不计入）
     *   wx = 1000 − 600 = 400   ✓
     * </pre>
     * 三种场景均正确。因此这里<b>必须</b>用 {@code yeepayWithdrawable}
     * （已扣提现单）而不是裸的 {@code sumDividedSettled}：
     * 后者会在场景 B 算成 {@code 400 − 600 = −200 → 0}，把微信真实可提的 400 抹掉。
     */
    public long wxpayWithdrawable(Long subjectId) {
        if (subjectId == null) {
            return 0L;
        }
        long total = totalAvailable(subjectId);
        long yeepay = yeepayWithdrawable(subjectId);
        return Math.max(0L, total - yeepay);
    }

    /**
     * 渠道余额自洽性检查。
     *
     * <p>正常情况下 {@code 微信可提现 + 易宝可提现} 应等于总可用余额。
     * 不等说明存在「已核销未分账」的易宝订单（易宝侧有、本地已计收入），
     * 或存在账实不符，记 warn 供运营核查。不抛异常、不阻断提现。
     */
    public void reconcileCheck(Long subjectId) {
        if (subjectId == null) {
            return;
        }
        long total = totalAvailable(subjectId);
        long yeepay = yeepayWithdrawable(subjectId);
        long sum = wxpayWithdrawable(subjectId) + yeepay;
        if (sum != total) {
            log.warn("渠道余额不自洽待核查 subjectId={} total={} wx={} yeepay={} diff={}",
                    subjectId, total, total - yeepay, yeepay, total - sum);
        }
    }

    /** 主体账户的可用余额（分）；无账户返回 0。 */
    public long totalAvailable(Long subjectId) {
        if (subjectId == null) {
            return 0L;
        }
        com.wuling.finance.entity.SubjectAccount account = subjectAccountMapper.selectOne(
                new LambdaQueryWrapper<com.wuling.finance.entity.SubjectAccount>()
                        .eq(com.wuling.finance.entity.SubjectAccount::getSubjectId, subjectId)
                        .last("limit 1"));
        return account == null ? 0L : nz(account.getAvailableBalance());
    }

    /** 已分账成功的结算记录合计（扣除已冲正部分）。 */
    private long sumDividedSettled(Long subjectId) {
        List<SettlementRecord> records = settlementRecordMapper.selectList(
                new LambdaQueryWrapper<SettlementRecord>()
                        .eq(SettlementRecord::getSubjectId, subjectId)
                        .eq(SettlementRecord::getStatus, LedgerService.SETTLE_SETTLED)
                        .eq(SettlementRecord::getDivideStatus, SettlementRecord.DIVIDE_SUCCESS));
        long sum = 0L;
        for (SettlementRecord record : records) {
            sum += nz(record.getAmount()) - nz(record.getRefundedAmount());
        }
        return sum;
    }

    /** 该主体已发起的易宝提现合计（含 PROCESSING / PAID，不含 FAILED / REJECTED）。 */
    private long sumYeepayWithdrawals(Long subjectId) {
        List<Withdrawal> withdrawals = withdrawalMapper.selectList(
                new LambdaQueryWrapper<Withdrawal>()
                        .eq(Withdrawal::getSubjectId, subjectId)
                        .eq(Withdrawal::getPayoutChannel, WithdrawalService.PAYOUT_CHANNEL_YEEPAY)
                        .in(Withdrawal::getStatus, List.of(
                                WithdrawalService.APPLIED,
                                WithdrawalService.AUDITING,
                                WithdrawalService.PROCESSING,
                                WithdrawalService.PAID)));
        long sum = 0L;
        for (Withdrawal w : withdrawals) {
            sum += nz(w.getAmount());
        }
        return sum;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
