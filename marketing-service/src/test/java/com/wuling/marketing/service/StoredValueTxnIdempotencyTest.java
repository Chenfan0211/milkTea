package com.wuling.marketing.service;

import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.StoredValueTxn;
import com.wuling.marketing.mapper.StoredValueTxnMapper;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueTxnIdempotencyTest {

    private final AppUserMapper appUserMapper = mock(AppUserMapper.class);
    private final StoredValueTxnMapper txnMapper = mock(StoredValueTxnMapper.class);
    private final StoredValueService service = new StoredValueService(
            null, null, null, null, appUserMapper, txnMapper);

    @Test
    @DisplayName("同一业务号重复扣款只执行一次余额更新")
    void repeatedPayUpdatesBalanceOnce() {
        StoredValueTxn existing = txn("ORDER-1", StoredValueTxn.PAY, 9L, 100L, StoredValueTxn.SUCCESS);
        existing.setId(1L);
        doAnswer(invocation -> {
            StoredValueTxn txn = invocation.getArgument(0);
            txn.setId(1L);
            return 1;
        }).when(txnMapper).insert(any(StoredValueTxn.class));
        when(txnMapper.markSuccess(1L)).thenReturn(1);
        when(appUserMapper.addBalance(9L, -100L)).thenReturn(1);

        service.payWithBalance(9L, 100L, "ORDER-1");

        when(txnMapper.insert(any(StoredValueTxn.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(txnMapper.selectByBizNoForUpdate("ORDER-1")).thenReturn(existing);
        service.payWithBalance(9L, 100L, "ORDER-1");

        verify(appUserMapper, times(1)).addBalance(9L, -100L);
        verify(txnMapper, times(1)).markSuccess(1L);
    }

    @Test
    @DisplayName("失败状态允许安全重试并完成一次扣款")
    void failedPayCanRetry() {
        StoredValueTxn failed = txn("ORDER-2", StoredValueTxn.PAY, 9L, 100L, StoredValueTxn.FAILED);
        failed.setId(2L);
        when(txnMapper.insert(any(StoredValueTxn.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(txnMapper.selectByBizNoForUpdate("ORDER-2")).thenReturn(failed);
        when(txnMapper.retryFailed(2L)).thenReturn(1);
        when(appUserMapper.addBalance(9L, -100L)).thenReturn(1);
        when(txnMapper.markSuccess(2L)).thenReturn(1);

        service.payWithBalance(9L, 100L, "ORDER-2");

        verify(txnMapper).retryFailed(2L);
        verify(appUserMapper).addBalance(9L, -100L);
        verify(txnMapper).markSuccess(2L);
    }

    @Test
    @DisplayName("余额不足写 FAILED 后抛出业务异常，便于下次安全重试")
    void insufficientBalanceMarksFailedThenThrows() {
        doAnswer(invocation -> {
            StoredValueTxn txn = invocation.getArgument(0);
            txn.setId(3L);
            return 1;
        }).when(txnMapper).insert(any(StoredValueTxn.class));
        when(appUserMapper.addBalance(9L, -100L)).thenReturn(0);
        when(txnMapper.markFailed(3L, "储值余额不足，请先充值")).thenReturn(1);

        assertThrows(BusinessException.class, () -> service.payWithBalance(9L, 100L, "ORDER-3"));

        verify(txnMapper).markFailed(3L, "储值余额不足，请先充值");
        verify(txnMapper, never()).markSuccess(anyLong());
    }

    @Test
    @DisplayName("同一业务号被另一资金操作占用时拒绝")
    void sameBizNoDifferentOperationRejected() {
        StoredValueTxn pay = txn("ORDER-4", StoredValueTxn.PAY, 9L, 100L, StoredValueTxn.SUCCESS);
        pay.setId(4L);
        when(txnMapper.insert(any(StoredValueTxn.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(txnMapper.selectByBizNoForUpdate("ORDER-4")).thenReturn(pay);

        assertThrows(BusinessException.class, () -> service.refundToBalance(9L, 100L, "ORDER-4"));

        verify(appUserMapper, never()).addBalance(anyLong(), anyLong());
    }

    @Test
    @DisplayName("同一业务号同操作但用户或金额不一致时拒绝")
    void sameBizNoDifferentRequestRejected() {
        StoredValueTxn success = txn("ORDER-5", StoredValueTxn.PAY, 9L, 100L, StoredValueTxn.SUCCESS);
        success.setId(5L);
        when(txnMapper.insert(any(StoredValueTxn.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(txnMapper.selectByBizNoForUpdate("ORDER-5")).thenReturn(success);

        assertThrows(BusinessException.class, () -> service.payWithBalance(9L, 200L, "ORDER-5"));

        verify(appUserMapper, never()).addBalance(anyLong(), anyLong());
    }

    private StoredValueTxn txn(String bizNo, String operation, Long userId, long amount, String status) {
        StoredValueTxn txn = new StoredValueTxn();
        txn.setBizNo(bizNo);
        txn.setOperationType(operation);
        txn.setUserId(userId);
        txn.setAmount(amount);
        txn.setStatus(status);
        return txn;
    }
}