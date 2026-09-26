package com.wuling.finance;

import com.wuling.finance.entity.Withdrawal;
import com.wuling.finance.mapper.WithdrawalMapper;
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
    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        withdrawalMapper = mock(WithdrawalMapper.class);
        ledgerService = mock(LedgerService.class);
        withdrawalService = new WithdrawalService(withdrawalMapper, ledgerService);
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
