package com.wuling.trade.service;

import com.wuling.trade.entity.BalancePayIntent;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.BalancePayIntentMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression coverage for funds returned by the payment reconciliation job. */
class BalancePayReconcileSafetyTest {
    private final BalancePayIntentMapper intents = mock(BalancePayIntentMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final StoredValueBalancePort balance = mock(StoredValueBalancePort.class);
    private BalancePayIntent intent;
    private Order order;
    private BalancePayReconcileJob job;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
        intent = new BalancePayIntent();
        intent.setId(1L); intent.setOrderNo("MO-RECONCILE"); intent.setUserId(9L);
        intent.setAmount(1L); intent.setStatus(BalancePayIntent.STATUS_PENDING);
        intent.setCreateTime(LocalDateTime.now().minusMinutes(5));
        order = new Order(); order.setId(2L); order.setOrderNo(intent.getOrderNo());
        order.setStatus(OrderService.STATUS_CREATED); order.setUserId(9L);
        when(intents.selectList(any())).thenAnswer(call ->
                BalancePayIntent.STATUS_PENDING.equals(intent.getStatus()) ? List.of(intent) : List.of());
        when(intents.selectOne(any())).thenReturn(intent);
        when(intents.update(any(BalancePayIntent.class), any())).thenAnswer(call -> {
            intent.setStatus(((BalancePayIntent) call.getArgument(0)).getStatus());
            return 1;
        });
        when(orders.selectOne(any())).thenReturn(order);
        when(payments.selectCount(any())).thenReturn(1L);
        when(balance.hasSuccessfulPay("PAY:MO-RECONCILE")).thenReturn(true);
        job = new BalancePayReconcileJob(intents, orders, payments, balance, true);
    }

    @Test void shippedOrderWithSuccessfulPaymentCannotBeCompensated() {
        order.setStatus(OrderService.STATUS_SHIPPED);
        job.reconcile();
        assertEquals(BalancePayIntent.STATUS_DONE, intent.getStatus());
        verify(balance, never()).refund(anyLong(), anyLong(), anyString(), any());
    }

    @Test void alreadyRefundedOrderWithPaymentCannotReceiveSecondCompensationRefund() {
        order.setStatus(OrderService.STATUS_REFUNDED);
        job.reconcile();
        assertEquals(BalancePayIntent.STATUS_DONE, intent.getStatus());
        verify(balance, never()).refund(anyLong(), anyLong(), anyString(), any());
    }

    @Test void insufficientBalanceFollowedBySweepStillAllowsSameOrderPaymentRegistration() {
        when(balance.hasSuccessfulPay("PAY:MO-RECONCILE")).thenReturn(false);
        when(intents.insert(any(BalancePayIntent.class))).thenThrow(new DuplicateKeyException("same order"));
        job.reconcile();
        assertEquals(BalancePayIntent.STATUS_NO_DEBIT, intent.getStatus());
        job.reconcile();
        verify(balance, times(1)).hasSuccessfulPay("PAY:MO-RECONCILE");
        BalancePayIntentService registrations = new BalancePayIntentService(intents);
        BalancePayIntent retry = assertDoesNotThrow(() -> registrations.register("MO-RECONCILE", 9L, 1L));
        assertEquals(BalancePayIntent.STATUS_PENDING, retry.getStatus());
        verify(balance, never()).refund(anyLong(), anyLong(), anyString(), any());
    }

    @Test void compensatedRealDebitCannotBeReopenedAsFreePayment() {
        job.reconcile();
        assertEquals(BalancePayIntent.STATUS_COMPENSATED, intent.getStatus());
        when(intents.insert(any(BalancePayIntent.class))).thenThrow(new DuplicateKeyException("same order"));
        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> new BalancePayIntentService(intents).register("MO-RECONCILE", 9L, 1L));
    }

    @Test void noDebitCannotBeReopenedWithDifferentUserOrAmount() {
        intent.setStatus(BalancePayIntent.STATUS_NO_DEBIT);
        when(intents.insert(any(BalancePayIntent.class))).thenThrow(new DuplicateKeyException("same order"));
        BalancePayIntentService registrations = new BalancePayIntentService(intents);
        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> registrations.register("MO-RECONCILE", 10L, 1L));
        assertThrows(com.wuling.common.exception.BusinessException.class,
                () -> registrations.register("MO-RECONCILE", 9L, 2L));
        assertEquals(BalancePayIntent.STATUS_NO_DEBIT, intent.getStatus());
    }

    @Test void paymentHoldingOrderLockCommitsBeforeReconcileCanDecideToRefund() throws Exception {
        ReentrantLock databaseOrderLock = new ReentrantLock();
        CountDownLatch reconcileReachedOrder = new CountDownLatch(1);
        CountingTransactions transactions = new CountingTransactions();
        when(orders.selectOne(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "FOR UPDATE must run inside the transaction protecting the funds decision");
            LambdaQueryWrapper<Order> query = call.getArgument(0);
            assertTrue(query.getSqlSegment().toLowerCase().contains("for update"));
            reconcileReachedOrder.countDown();
            if (!databaseOrderLock.tryLock(5, TimeUnit.SECONDS)) throw new IllegalStateException("order lock timeout");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { databaseOrderLock.unlock(); }
            });
            return order;
        });
        job = new BalancePayReconcileJob(intents, orders, payments, balance, true, transactions);
        var executor = Executors.newSingleThreadExecutor();
        databaseOrderLock.lock(); // The normal payment transaction already owns the order row.
        try {
            var reconciliation = executor.submit(job::reconcile);
            assertTrue(reconcileReachedOrder.await(5, TimeUnit.SECONDS));
            order.setStatus(OrderService.STATUS_PAID); // Payment commits while reconciliation waits.
            databaseOrderLock.unlock();
            reconciliation.get(5, TimeUnit.SECONDS);
            assertEquals(BalancePayIntent.STATUS_DONE, intent.getStatus());
            assertEquals(1, transactions.commits);
            verify(balance, never()).refund(anyLong(), anyLong(), anyString(), any());
        } finally {
            if (databaseOrderLock.isHeldByCurrentThread()) databaseOrderLock.unlock();
            executor.shutdownNow();
        }
    }

    @Test void fundsCheckAndCompensationRunInsideTheSamePerIntentTransaction() {
        CountingTransactions transactions = new CountingTransactions();
        when(orders.selectOne(any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            LambdaQueryWrapper<Order> query = call.getArgument(0);
            assertTrue(query.getSqlSegment().toLowerCase().contains("for update"));
            return order;
        });
        when(balance.hasSuccessfulPay("PAY:MO-RECONCILE")).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); return true;
        });
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, transactions.commits, "order lock must survive until remote compensation completes");
            return null;
        }).when(balance).refund(eq(9L), eq(1L), eq("COMP:MO-RECONCILE"), isNull());
        new BalancePayReconcileJob(intents, orders, payments, balance, true, transactions).reconcile();
        assertEquals(BalancePayIntent.STATUS_COMPENSATED, intent.getStatus());
        assertEquals(1, transactions.commits);
    }

    private static class CountingTransactions extends AbstractPlatformTransactionManager {
        int commits;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) { commits++; }
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
