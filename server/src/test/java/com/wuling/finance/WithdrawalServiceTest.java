package com.wuling.finance;

import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.WithdrawalMapper;
import com.wuling.finance.port.PayoutPort;
import com.wuling.finance.service.LedgerService;
import com.wuling.finance.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提现服务记账联动测试。
 *
 * <p>提现流水必须统一经过 LedgerService，并携带提现单号、冻结余额桶和正确符号。
 */
class WithdrawalServiceTest {

    private WithdrawalMapper withdrawalMapper;
    private LedgerService ledgerService;
    private PayoutPort payoutPort;
    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        withdrawalMapper = mock(WithdrawalMapper.class);
        ledgerService = mock(LedgerService.class);
        payoutPort = mock(PayoutPort.class);
        // 默认 mock 通道语义：出款即时到账（维持既有测试期望）
        when(payoutPort.transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayoutPort.PayoutResult(true, true, "MOCK-BATCH", null));
        withdrawalService = new WithdrawalService(withdrawalMapper, ledgerService, payoutPort);
    }

    @Test
    void applyOverLimitFreezesAvailableBalanceAndWaitsForReview() {
        Withdrawal result = withdrawalService.apply(11L, 21L, "STORE", 20_000L);

        assertEquals(WithdrawalService.APPLIED, result.getStatus());
        assertNotNull(result.getWithdrawNo());
        verify(withdrawalMapper).insert(result);

        LedgerService.Posting posting = captureSinglePosting();
        assertEquals(21L, posting.subjectId());
        assertEquals("STORE", posting.roleType());
        assertEquals("FREEZE", posting.type());
        assertEquals(20_000L, posting.changeAmount());
        assertEquals(LedgerService.BUCKET_FROZEN, posting.balanceBucket());
        assertEquals("WITHDRAWAL", posting.bizType());
        assertEquals(result.getWithdrawNo(), posting.bizNo());
    }

    @Test
    void applyInstantWithdrawalFreezesThenWithdrawsFromFrozenBalance() {
        Withdrawal result = withdrawalService.apply(12L, 22L, "SUPPLIER", WithdrawalService.INSTANT_LIMIT);

        assertEquals(WithdrawalService.PAID, result.getStatus());
        verify(withdrawalMapper).insert(result);
        List<LedgerService.Posting> postings = capturePostings();

        assertEquals(2, postings.size());
        assertPosting(postings.get(0), 22L, "SUPPLIER", "FREEZE",
                WithdrawalService.INSTANT_LIMIT, result.getWithdrawNo());
        assertPosting(postings.get(1), 22L, "SUPPLIER", "WITHDRAW",
                -WithdrawalService.INSTANT_LIMIT, result.getWithdrawNo());
    }

    @Test
    void reviewApproveWithdrawsFrozenBalance() {
        Withdrawal withdrawal = withdrawal(31L, 32L, "STORE", 15_000L, WithdrawalService.APPLIED);
        when(withdrawalMapper.selectById(31L)).thenReturn(withdrawal);

        Withdrawal result = withdrawalService.review(31L, true, null);

        assertEquals(WithdrawalService.PAID, result.getStatus());
        verify(withdrawalMapper).updateById(withdrawal);
        LedgerService.Posting posting = captureSinglePosting();
        assertPosting(posting, 32L, "STORE", "WITHDRAW", -15_000L, withdrawal.getWithdrawNo());
    }

    @Test
    void reviewRejectUnfreezesFrozenBalance() {
        Withdrawal withdrawal = withdrawal(41L, 42L, "STORE", 18_000L, WithdrawalService.APPLIED);
        when(withdrawalMapper.selectById(41L)).thenReturn(withdrawal);

        Withdrawal result = withdrawalService.review(41L, false, "资料不完整");

        assertEquals(WithdrawalService.REJECTED, result.getStatus());
        assertEquals("资料不完整", result.getFailureReason());
        verify(withdrawalMapper).updateById(withdrawal);
        LedgerService.Posting posting = captureSinglePosting();
        assertPosting(posting, 42L, "STORE", "UNFREEZE", -18_000L, withdrawal.getWithdrawNo());
    }

    @Test
    void markFailedUnfreezesFrozenBalance() {
        Withdrawal withdrawal = withdrawal(51L, 52L, "CHANNEL", 19_000L, WithdrawalService.AUDITING);
        when(withdrawalMapper.selectById(51L)).thenReturn(withdrawal);

        Withdrawal result = withdrawalService.markFailed(51L, "出款通道失败");

        assertEquals(WithdrawalService.FAILED, result.getStatus());
        assertEquals("出款通道失败", result.getFailureReason());
        verify(withdrawalMapper).updateById(withdrawal);
        LedgerService.Posting posting = captureSinglePosting();
        assertPosting(posting, 52L, "CHANNEL", "UNFREEZE", -19_000L, withdrawal.getWithdrawNo());
    }

    @Test
    void applyInstantWxpayChannelMarksProcessingNotPaid() {
        // wxpay 通道：受理成功但非即时到账 → 置 PROCESSING，不记账 PAID
        when(payoutPort.transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayoutPort.PayoutResult(true, false, "WX-BATCH-1", null));

        Withdrawal result = withdrawalService.apply(12L, 22L, "SUPPLIER", WithdrawalService.INSTANT_LIMIT);

        assertEquals(WithdrawalService.PROCESSING, result.getStatus(), "wxpay 通道受理后应置 PROCESSING，而非 PAID");
        assertEquals("WX-BATCH-1", result.getTransferBatchNo());
        verify(withdrawalMapper).updateById(result);
        // 只有 FREEZE 一条流水，没有 WITHDRAW（等回调确认才扣减）
        List<LedgerService.Posting> postings = capturePostingsOrOne();
        assertEquals(1, postings.size());
        assertEquals("FREEZE", postings.get(0).type());
    }

    @Test
    void applyPayoutRejectedUnfreezesAndMarksFailed() {
        // 出款受理失败 → FAILED + 解冻
        when(payoutPort.transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayoutPort.PayoutResult(false, false, null, "单笔超过 2000 元上限"));

        Withdrawal result = withdrawalService.apply(12L, 22L, "SUPPLIER", WithdrawalService.INSTANT_LIMIT);

        assertEquals(WithdrawalService.FAILED, result.getStatus());
        assertEquals("单笔超过 2000 元上限", result.getFailureReason());
        List<LedgerService.Posting> postings = capturePostings();
        assertEquals(2, postings.size(), "FREEZE + UNFREEZE 两条");
        assertEquals("FREEZE", postings.get(0).type());
        assertEquals("UNFREEZE", postings.get(1).type());
    }

    @Test
    void confirmPaidConvergesProcessingToPaid() {
        Withdrawal processing = withdrawal(61L, 62L, "STORE", 10_000L, WithdrawalService.PROCESSING);
        when(withdrawalMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(processing);

        withdrawalService.confirmPaid(processing.getWithdrawNo());

        assertEquals(WithdrawalService.PAID, processing.getStatus());
        verify(withdrawalMapper).updateById(processing);
        LedgerService.Posting posting = captureSinglePosting();
        assertEquals("WITHDRAW", posting.type());
        assertEquals(-10_000L, posting.changeAmount());
    }

    @Test
    void confirmFailedUnfreezesProcessing() {
        Withdrawal processing = withdrawal(71L, 72L, "STORE", 10_000L, WithdrawalService.PROCESSING);
        when(withdrawalMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(processing);

        withdrawalService.confirmFailed(processing.getWithdrawNo(), "微信转账失败");

        assertEquals(WithdrawalService.FAILED, processing.getStatus());
        assertEquals("微信转账失败", processing.getFailureReason());
        LedgerService.Posting posting = captureSinglePosting();
        assertEquals("UNFREEZE", posting.type());
    }

    private List<LedgerService.Posting> capturePostingsOrOne() {
        ArgumentCaptor<LedgerService.Posting> captor = ArgumentCaptor.forClass(LedgerService.Posting.class);
        verify(ledgerService, org.mockito.Mockito.atLeast(1)).post(captor.capture());
        return captor.getAllValues();
    }

    private Withdrawal withdrawal(Long id, Long subjectId, String roleType, long amount, String status) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setId(id);
        withdrawal.setWithdrawNo("WD20260927000001");
        withdrawal.setSubjectId(subjectId);
        withdrawal.setRoleType(roleType);
        withdrawal.setAmount(amount);
        withdrawal.setStatus(status);
        withdrawal.setApplyTime(LocalDateTime.now());
        return withdrawal;
    }

    private LedgerService.Posting captureSinglePosting() {
        ArgumentCaptor<LedgerService.Posting> captor = ArgumentCaptor.forClass(LedgerService.Posting.class);
        verify(ledgerService).post(captor.capture());
        return captor.getValue();
    }

    private List<LedgerService.Posting> capturePostings() {
        ArgumentCaptor<LedgerService.Posting> captor = ArgumentCaptor.forClass(LedgerService.Posting.class);
        verify(ledgerService, org.mockito.Mockito.times(2)).post(captor.capture());
        return captor.getAllValues();
    }

    private void assertPosting(LedgerService.Posting posting,
                               Long subjectId,
                               String roleType,
                               String type,
                               long changeAmount,
                               String withdrawNo) {
        assertEquals(subjectId, posting.subjectId());
        assertEquals(roleType, posting.roleType());
        assertEquals(type, posting.type());
        assertEquals(changeAmount, posting.changeAmount());
        assertEquals(LedgerService.BUCKET_FROZEN, posting.balanceBucket());
        assertEquals("WITHDRAWAL", posting.bizType());
        assertEquals(withdrawNo, posting.bizNo());
    }
}
