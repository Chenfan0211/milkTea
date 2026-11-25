package com.wuling.finance;

import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.WithdrawalMapper;
import com.wuling.finance.port.PayoutAccountPort;
import com.wuling.finance.port.PayoutPort;
import com.wuling.finance.service.ChannelBalanceService;
import com.wuling.finance.service.LedgerService;
import com.wuling.finance.service.WithdrawalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提现服务记账联动测试。
 *
 * <p>提现流水必须统一经过 LedgerService，并携带提现单号、冻结余额桶和正确符号。
 *
 * <p>测试走 <b>mock 出款通道</b>（immediatePaid=true），
 * 因此不涉及易宝闸门；易宝分支的闸门与异步结算由
 * {@code WithdrawalServiceYeepayTest} 单独覆盖。
 */
class WithdrawalServiceTest {

    private WithdrawalMapper withdrawalMapper;
    private LedgerService ledgerService;
    private PayoutPort payoutPort;
    private PayoutAccountPort payoutAccountPort;
    private ChannelBalanceService channelBalanceService;
    private WithdrawalService withdrawalService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        withdrawalMapper = mock(WithdrawalMapper.class);
        ledgerService = mock(LedgerService.class);
        payoutPort = mock(PayoutPort.class);
        payoutAccountPort = mock(PayoutAccountPort.class);
        channelBalanceService = mock(ChannelBalanceService.class);
        ObjectProvider<com.wuling.subject.onboard.MerchantOnboardService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        // 默认 mock 通道语义：出款即时到账（维持既有测试期望）
        when(payoutPort.transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayoutPort.PayoutResult(true, true, "MOCK-BATCH", null));

        withdrawalService = new WithdrawalService(withdrawalMapper, ledgerService, payoutPort,
                payoutAccountPort, provider, channelBalanceService, "mock",
                emptyProvider(), emptyProvider());
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
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
    void applySmallWithdrawalFreezesAndWaitsForReview() {
        Withdrawal result = withdrawalService.apply(12L, 22L, "SUPPLIER", 1L);

        assertEquals(WithdrawalService.APPLIED, result.getStatus());
        assertNull(result.getReviewTime());
        verify(withdrawalMapper).insert(result);
        assertPosting(captureSinglePosting(), 22L, "SUPPLIER", "FREEZE", 1L, result.getWithdrawNo());
        verify(payoutPort, never()).transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void reviewApproveWithdrawsFrozenBalance() {
        Withdrawal withdrawal = withdrawal(31L, 32L, "STORE", 15_000L, WithdrawalService.APPLIED);
        when(withdrawalMapper.selectByIdForUpdate(31L)).thenReturn(withdrawal);

        Withdrawal result = withdrawalService.review(31L, true, null);

        assertEquals(WithdrawalService.PAID, result.getStatus());
        verify(withdrawalMapper).updateById(withdrawal);
        LedgerService.Posting posting = captureSinglePosting();
        assertPosting(posting, 32L, "STORE", "WITHDRAW", -15_000L, withdrawal.getWithdrawNo());
    }

    @Test
    void reviewRejectUnfreezesFrozenBalance() {
        Withdrawal withdrawal = withdrawal(41L, 42L, "STORE", 18_000L, WithdrawalService.APPLIED);
        when(withdrawalMapper.selectByIdForUpdate(41L)).thenReturn(withdrawal);

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
        when(withdrawalMapper.selectByIdForUpdate(51L)).thenReturn(withdrawal);

        Withdrawal result = withdrawalService.markFailed(51L, "出款通道失败");

        assertEquals(WithdrawalService.FAILED, result.getStatus());
        assertEquals("出款通道失败", result.getFailureReason());
        verify(withdrawalMapper).updateById(withdrawal);
        LedgerService.Posting posting = captureSinglePosting();
        assertPosting(posting, 52L, "CHANNEL", "UNFREEZE", -19_000L, withdrawal.getWithdrawNo());
    }

    @Test
    void reviewApproveAsyncChannelMarksProcessingNotPaid() {
        // 真实通道（微信/易宝）只在后台审核通过后发起，受理成功后等待回调。
        when(payoutPort.transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayoutPort.PayoutResult(true, false, "WX-BATCH-1", null));

        Withdrawal withdrawal = withdrawal(12L, 22L, "SUPPLIER", 10_000L, WithdrawalService.APPLIED);
        when(withdrawalMapper.selectByIdForUpdate(12L)).thenReturn(withdrawal);
        Withdrawal result = withdrawalService.review(12L, true, null);

        assertEquals(WithdrawalService.PROCESSING, result.getStatus(), "异步通道受理后应置 PROCESSING，而非 PAID");
        assertEquals("WX-BATCH-1", result.getTransferBatchNo());
        verify(withdrawalMapper).updateById(result);
        // 审核阶段仍不扣减冻结余额，等回调确认才记 WITHDRAW。
        verify(ledgerService, never()).post(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reviewPayoutRejectedUnfreezesAndMarksFailed() {
        // 出款受理失败 → FAILED + 解冻
        when(payoutPort.transfer(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PayoutPort.PayoutResult(false, false, null, "单笔超过 2000 元上限"));

        Withdrawal withdrawal = withdrawal(12L, 22L, "SUPPLIER", 10_000L, WithdrawalService.APPLIED);
        when(withdrawalMapper.selectByIdForUpdate(12L)).thenReturn(withdrawal);
        Withdrawal result = withdrawalService.review(12L, true, null);

        assertEquals(WithdrawalService.FAILED, result.getStatus());
        assertEquals("单笔超过 2000 元上限", result.getFailureReason());
        assertEquals("UNFREEZE", captureSinglePosting().type());
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

        withdrawalService.confirmFailed(processing.getWithdrawNo(), "转账失败");

        assertEquals(WithdrawalService.FAILED, processing.getStatus());
        assertEquals("转账失败", processing.getFailureReason());
        LedgerService.Posting posting = captureSinglePosting();
        assertEquals("UNFREEZE", posting.type());
    }

    private Withdrawal withdrawal(Long id, Long subjectId, String roleType, long amount, String status) {
        Withdrawal withdrawal = new Withdrawal();
        withdrawal.setId(id);
        withdrawal.setWithdrawNo("WD20260927000001");
        withdrawal.setSubjectId(subjectId);
        withdrawal.setRoleType(roleType);
        withdrawal.setAmount(amount);
        withdrawal.setStatus(status);
        withdrawal.setPayoutChannel("MOCK");
        withdrawal.setApplyTime(LocalDateTime.now());
        return withdrawal;
    }

    private LedgerService.Posting captureSinglePosting() {
        ArgumentCaptor<LedgerService.Posting> captor = ArgumentCaptor.forClass(LedgerService.Posting.class);
        verify(ledgerService).post(captor.capture());
        return captor.getValue();
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
