package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.common.outbox.OutboxService;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.mapper.RefundMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.port.SettlementQueryPort;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅使用本地替身模拟支付、库存和持久化，不连接真实资金通道。 */
class RefundServiceSafetyTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderItemMapper items = mock(OrderItemMapper.class);
    private final RefundMapper refunds = mock(RefundMapper.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final MallAfterSaleMapper afterSales = mock(MallAfterSaleMapper.class);
    private final OrderService orderService = mock(OrderService.class);
    private final SettlementQueryPort settlement = mock(SettlementQueryPort.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final MallStockService stock = mock(MallStockService.class);
    private final StoredValueBalancePort balance = mock(StoredValueBalancePort.class);
    private final PaymentGateway wxpay = mock(PaymentGateway.class);
    private final PaymentGateway mockpay = mock(PaymentGateway.class);
    private RefundService service;
    private RefundService proxy;
    private LocalTransactionManager transactionManager;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Refund.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), MallAfterSale.class);
        when(wxpay.channel()).thenReturn("WXPAY");
        when(mockpay.channel()).thenReturn("MOCK");
        service = new RefundService(orders, items, refunds, payments, settlement, orderService,
                new PaymentGatewayResolver(List.of(wxpay, mockpay), "mock"), outbox, balance, stock, afterSales);
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        transactionManager = new LocalTransactionManager();
        factory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        proxy = (RefundService) factory.getProxy();
        order = new Order();
        order.setId(1L);
        order.setOrderNo("MO-1");
        order.setOrderType("MALL");
        order.setStatus("PAID");
        order.setPaidAmount(20_000L);
        order.setUserId(8L);
        payment = new Payment();
        payment.setOrderId(1L);
        payment.setOrderNo("MO-1");
        payment.setBizType(Payment.BIZ_ORDER);
        payment.setStandardStatus("PAID");
        payment.setChannel("WXPAY");
        payment.setAmount(20_000L);
        when(payments.selectOne(any())).thenReturn(payment);
        when(orders.selectById(1L)).thenReturn(order);
        when(orderService.lockForUpdate("MO-1")).thenReturn(order);
        OrderItem defaultItem = new OrderItem();
        defaultItem.setId(31L);
        defaultItem.setOrderId(1L);
        defaultItem.setQuantity(10);
        defaultItem.setUnitPrice(2_000L);
        defaultItem.setSupplierSubjectId(401L);
        when(items.selectList(any())).thenReturn(List.of(defaultItem));
        SettlementQueryPort.SettlementStatus status = new SettlementQueryPort.SettlementStatus();
        status.setHasSettlement(true);
        when(settlement.query(1L)).thenReturn(status);
        when(refunds.update(isNull(), any())).thenReturn(1);
        doAnswer(invocation -> { ((Refund) invocation.getArgument(0)).setId(11L); return 1; })
                .when(refunds).insert(any(Refund.class));
    }

    @Test
    void partialRefundUsesRecordedChannelAndOriginalPaymentTotal() {
        order.setStatus("SHIPPED");
        // 订单展示快照不参与原支付总额的计算，退款必须使用支付记录。
        order.setPaidAmount(19_000L);
        MallAfterSale afterSale = afterSale();

        Refund refund = proxy.refundAfterSale(order, afterSale, "退一件");

        verify(wxpay).refund(eq("MO-1"), anyString(), eq(2_000L), eq(20_000L), eq("退一件"));
        verify(mockpay, never()).refund(anyString(), anyString(), anyLong(), anyLong(), any());
        assertEquals(20_000L, refund.getOriginalAmount());
    }

    @Test
    void wholeRefundKeepsOrderLockAndGatewayCallInOneTransaction() {
        when(orderService.lockForUpdate("MO-1")).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "订单锁必须属于退款入口事务");
            return order;
        });
        proxy.refund("MO-1", "取消");
    }

    @Test
    void reconciliationUsesRecordedChannelAndTransactionForStateAndSideEffects() {
        Refund refund = refund();
        when(refunds.selectOne(any())).thenReturn(refund);
        when(wxpay.queryRefund("MO-1", "RF-1")).thenReturn("SUCCESS");
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "主动查单推进必须处于事务");
            return null;
        }).when(orderService).markRefunded(order);

        assertTrue(proxy.syncRefundStatus("MO-1", "RF-1"));
        verify(wxpay).queryRefund("MO-1", "RF-1");
        verify(mockpay, never()).queryRefund(anyString(), anyString());
    }

    @Test
    void manualReconciliationAlsoKeepsStateChangesInTransaction() {
        when(refunds.selectOne(any())).thenReturn(refund());
        when(refunds.selectById(11L)).thenReturn(refund());
        when(wxpay.queryRefund("MO-1", "RF-1")).thenReturn("SUCCESS");
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(orderService).markRefunded(order);

        assertTrue(proxy.syncRefundStatusByRefundNo("RF-1").advanced());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notificationAndReconciliationLockOrderBeforeCurrentRefundRead(boolean reconcile) {
        AtomicBoolean orderLocked = new AtomicBoolean();
        AtomicBoolean refundLocked = new AtomicBoolean();
        when(orderService.lockForUpdate("MO-1")).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            orderLocked.set(true);
            return order;
        });
        when(refunds.selectOne(any())).thenAnswer(invocation -> {
            Wrapper<Refund> query = invocation.getArgument(0);
            if (query.getSqlSegment().contains("for update")) {
                assertTrue(orderLocked.get(), "退款行锁之前必须先获得订单锁");
                refundLocked.set(true);
            }
            return refund();
        });
        when(wxpay.queryRefund("MO-1", "RF-1")).thenReturn("SUCCESS");

        assertTrue(reconcile ? proxy.syncRefundStatus("MO-1", "RF-1")
                : proxy.onRefundResult("RF-1", true, null));

        assertTrue(refundLocked.get());
    }

    @Test
    void quickRecheckStartsAfterCommitAndEntersTransactionThroughProxy() throws Exception {
        CountDownLatch query = new CountDownLatch(1);
        AtomicBoolean commitChecked = new AtomicBoolean();
        transactionManager.beforeCommit = () -> {
            if (commitChecked.compareAndSet(false, true)) {
                try {
                    assertFalse(query.await(100, TimeUnit.MILLISECONDS), "提交之前不能开始即时重查");
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
            }
        };
        when(wxpay.queryRefund(anyString(), anyString())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "异步重查也必须通过事务代理");
            query.countDown();
            return "PROCESSING";
        });
        when(wxpay.refund(anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(new PaymentGateway.RefundApplyResult(true, "WX-RF-1", null));
        when(refunds.selectOne(any())).thenReturn(refund());
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("refundService", proxy);
            context.refresh();
            context.getAutowireCapableBeanFactory().autowireBean(service);
            ReflectionTestUtils.setField(service, "quickRecheckEnabled", true);
            ReflectionTestUtils.setField(service, "quickRecheckDelayMs", 0L);

            proxy.refund("MO-1", "取消");

            assertTrue(commitChecked.get(), "退款必须完成外部入口事务");
            assertTrue(query.await(2, TimeUnit.SECONDS), "提交后应执行即时重查");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateOrConcurrentCallbacksOnlyWinningTransitionRestoresStockAndEmitsEvent(boolean partial) throws Exception {
        AtomicReference<String> persistedStatus = new AtomicReference<>("REFUNDING");
        CountDownLatch bothRead = new CountDownLatch(2);
        MallAfterSale afterSale = afterSale();
        if (partial) {
            when(afterSales.selectById(21L)).thenReturn(afterSale);
            OrderItem item = new OrderItem();
            item.setId(31L);
            item.setQuantity(2);
            item.setUnitPrice(2_000L);
            item.setSupplierSubjectId(401L);
            when(items.selectList(any())).thenReturn(List.of(item));
            when(stock.restoreForAfterSale(order, afterSale)).thenReturn(Map.of(31L, 1));
        }
        when(refunds.selectOne(any())).thenAnswer(invocation -> {
            Refund snapshot = refund();
            if (partial) snapshot.setAfterSaleId(21L);
            bothRead.countDown();
            assertTrue(bothRead.await(2, TimeUnit.SECONDS));
            return snapshot;
        });
        when(refunds.update(isNull(), any())).thenAnswer(invocation -> {
            Wrapper<Refund> condition = invocation.getArgument(1);
            assertTrue(condition.getSqlSegment().contains("status"), "状态写入必须限定原REFUNDING状态");
            return persistedStatus.compareAndSet("REFUNDING", "SUCCESS") ? 1 : 0;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> proxy.onRefundResult("RF-1", true, null));
            var second = executor.submit(() -> proxy.onRefundResult("RF-1", true, null));
            assertNotEquals(first.get(4, TimeUnit.SECONDS), second.get(4, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals("SUCCESS", persistedStatus.get());
        if (partial) {
            verify(stock, times(1)).restoreForAfterSale(order, afterSale);
            verify(orderService, times(1)).markAfterSaleRefunded(order, 1, 2);
        } else {
            verify(stock, times(1)).restoreForRefund(order);
            verify(orderService, times(1)).markRefunded(order);
        }
        verify(outbox, times(1)).enqueue(anyString(), anyString(), eq("ORDER_REFUNDED"),
                anyString(), anyString(), any());
    }

    @Test
    void duplicateFailedTransitionDoesNotOverwriteOrderOrAfterSale() {
        Refund refund = refund();
        refund.setAfterSaleId(21L);
        when(refunds.selectOne(any())).thenReturn(refund);
        when(refunds.update(isNull(), any())).thenReturn(0);

        assertFalse(proxy.onRefundResult("RF-1", false, "CLOSED"));

        verifyNoInteractions(stock, afterSales, outbox);
        verify(orderService, never()).markAfterSaleRefundFailed(any());
    }

    @Test
    void concurrentRetriesClaimFailedRefundBeforeCallingGateway() throws Exception {
        AtomicReference<String> persistedStatus = new AtomicReference<>("FAILED");
        CountDownLatch bothRead = new CountDownLatch(2);
        when(refunds.selectById(11L)).thenAnswer(invocation -> {
            Refund snapshot = refund();
            snapshot.setStatus("FAILED");
            bothRead.countDown();
            assertTrue(bothRead.await(2, TimeUnit.SECONDS));
            return snapshot;
        });
        when(refunds.selectOne(any())).thenAnswer(invocation -> {
            Refund current = refund();
            current.setStatus("FAILED");
            return current;
        });
        when(refunds.update(isNull(), any())).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Wrapper<Refund> condition = invocation.getArgument(1);
            assertTrue(condition.getSqlSegment().contains("status"));
            return persistedStatus.compareAndSet("FAILED", "REFUNDING") ? 1 : 0;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> retry = () -> {
                try {
                    proxy.retry(11L);
                    return true;
                } catch (com.wuling.common.exception.BusinessException alreadyClaimed) {
                    return false;
                }
            };
            var first = executor.submit(retry);
            var second = executor.submit(retry);
            assertNotEquals(first.get(4, TimeUnit.SECONDS), second.get(4, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        verify(wxpay, times(1)).refund(eq("MO-1"), anyString(), eq(2_000L), eq(20_000L), any());
    }

    @Test
    void retryRejectsOldFullAfterSaleAfterReplacementRefundUsedOneItem() {
        Refund failed = failedAfterSale(200L, 2);
        when(afterSales.selectList(any())).thenReturn(List.of(occupiedAfterSale(1)));
        when(refunds.selectList(any())).thenReturn(List.of(completedReplacement(100L)));

        assertThrows(BusinessException.class, () -> proxy.retry(11L));

        assertEquals("FAILED", failed.getStatus());
        assertEquals("RF-1", failed.getRefundNo());
        verify(orderService).lockForUpdate("MO-1");
        verify(wxpay, never()).refund(anyString(), anyString(), anyLong(), anyLong(), any());
        verifyNoInteractions(balance, stock, outbox);
    }

    @Test
    void retryRejectsOldAfterSaleWhileReplacementApplicationIsPending() {
        failedAfterSale(100L, 1);
        MallAfterSale pending = afterSale();
        pending.setId(22L);
        pending.setStatus("PENDING");
        when(afterSales.selectList(any())).thenReturn(List.of(pending));

        assertThrows(BusinessException.class, () -> proxy.retry(11L));

        verify(wxpay, never()).refund(anyString(), anyString(), anyLong(), anyLong(), any());
        verify(refunds, never()).update(isNull(), any());
    }

    @Test
    void retryRejectsTotalRefundAmountAboveOriginalPaymentEvenWhenQuantityFits() {
        failedAfterSale(150L, 1);
        when(afterSales.selectList(any())).thenReturn(List.of(occupiedAfterSale(1)));
        when(refunds.selectList(any())).thenReturn(List.of(completedReplacement(100L)));

        assertThrows(BusinessException.class, () -> proxy.retry(11L));

        verify(wxpay, never()).refund(anyString(), anyString(), anyLong(), anyLong(), any());
        verify(refunds, never()).update(isNull(), any());
    }

    @Test
    void retryAllowsOnlyRemainingQuantityAndAmountAfterLockingOrder() {
        failedAfterSale(100L, 1);
        when(afterSales.selectList(any())).thenReturn(List.of(occupiedAfterSale(1)));
        when(refunds.selectList(any())).thenReturn(List.of(completedReplacement(100L)));
        when(orderService.lockForUpdate("MO-1")).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(java.sql.Connection.TRANSACTION_READ_COMMITTED,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return order;
        });

        proxy.retry(11L);

        var sequence = inOrder(orderService, afterSales, refunds);
        sequence.verify(orderService).lockForUpdate("MO-1");
        sequence.verify(refunds).selectOne(any());
        sequence.verify(refunds).selectList(any());
        sequence.verify(afterSales).selectOne(any());
        sequence.verify(afterSales).selectList(any());
        verify(wxpay).refund(eq("MO-1"), anyString(), eq(100L), eq(200L), any());
    }

    @Test
    void retryReadsReplacementCommittedWhileWaitingForOrderLockEvenInOuterRepeatableReadTransaction() {
        failedAfterSale(200L, 2);
        AtomicBoolean replacementCommitted = new AtomicBoolean();
        when(orderService.lockForUpdate("MO-1")).thenAnswer(invocation -> {
            replacementCommitted.set(true);
            return order;
        });
        when(afterSales.selectList(any())).thenAnswer(invocation -> {
            Wrapper<MallAfterSale> currentRead = invocation.getArgument(0);
            assertTrue(currentRead.getSqlSegment().contains("for update"), "外层RR事务必须使用当前读");
            assertTrue(replacementCommitted.get());
            assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            return List.of(occupiedAfterSale(1));
        });
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThrows(BusinessException.class, () -> outer.execute(status -> proxy.retry(11L)));

        verify(afterSales).selectList(any());
        verify(wxpay, never()).refund(anyString(), anyString(), anyLong(), anyLong(), any());
    }

    @Test
    void uncertainRefundSubmissionKeepsSameNumberUntilQueryOrNotificationConfirmsIt() {
        order.setStatus("SHIPPED");
        MallAfterSale afterSale = afterSale();
        when(afterSales.selectById(21L)).thenReturn(afterSale);
        when(stock.restoreForAfterSale(order, afterSale)).thenReturn(Map.of());
        when(wxpay.refund(anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(new PaymentGateway.RefundApplyResult(false, null, "timeout", true));

        Refund unknown = proxy.refundAfterSale(order, afterSale, "退货");

        assertEquals("REFUNDING", unknown.getStatus());
        String originalRefundNo = unknown.getRefundNo();
        when(refunds.selectById(11L)).thenReturn(unknown);
        when(refunds.selectOne(any())).thenReturn(unknown);
        assertThrows(BusinessException.class, () -> proxy.retry(11L));
        assertEquals(originalRefundNo, unknown.getRefundNo());
        assertTrue(proxy.onRefundResult(originalRefundNo, true, null));
        assertEquals("SUCCESS", unknown.getStatus());
        verify(wxpay, times(1)).refund(eq("MO-1"), eq(originalRefundNo), eq(2_000L), eq(20_000L), any());
        verify(orderService, never()).markAfterSaleRefundFailed(any());
    }

    private Refund failedAfterSale(long amount, int quantity) {
        order.setStatus("SHIPPED");
        order.setPaidAmount(200L);
        order.setRefundStatus("REFUNDED");
        payment.setAmount(200L);
        Refund failed = refund();
        failed.setStatus("FAILED");
        failed.setAfterSaleId(21L);
        failed.setAmount(amount);
        failed.setOriginalAmount(200L);
        MallAfterSale afterSale = afterSale();
        afterSale.setStatus("FAILED");
        afterSale.setRefundAmount(amount);
        afterSale.setItems("[{\"orderItemId\":31,\"quantity\":" + quantity + "}]");
        afterSale.setReturnTrackingNo("QA-RETURN-31");
        afterSale.setReturnReceivedTime(java.time.LocalDateTime.now());
        OrderItem item = new OrderItem();
        item.setId(31L);
        item.setOrderId(1L);
        item.setQuantity(2);
        item.setUnitPrice(100L);
        when(items.selectList(any())).thenReturn(List.of(item));
        when(refunds.selectById(11L)).thenReturn(failed);
        when(refunds.selectOne(any())).thenReturn(failed);
        when(afterSales.selectById(21L)).thenReturn(afterSale);
        when(afterSales.selectOne(any())).thenReturn(afterSale);
        return failed;
    }

    private MallAfterSale occupiedAfterSale(int quantity) {
        MallAfterSale replacement = afterSale();
        replacement.setId(22L);
        replacement.setStatus("SUCCESS");
        replacement.setItems("[{\"orderItemId\":31,\"quantity\":" + quantity + "}]");
        return replacement;
    }

    private Refund completedReplacement(long amount) {
        Refund replacement = refund();
        replacement.setId(12L);
        replacement.setRefundNo("RF-B");
        replacement.setAfterSaleId(22L);
        replacement.setStatus("SUCCESS");
        replacement.setAmount(amount);
        return replacement;
    }

    @Test
    void separateStoredValueAfterSalesUseSeparateStableRefundBusinessNumbers() {
        payment.setChannel("STORED_VALUE");
        order.setStatus("SHIPPED");
        MallAfterSale first = afterSale();
        MallAfterSale second = afterSale();
        second.setId(22L);
        when(afterSales.selectById(21L)).thenReturn(first);
        when(afterSales.selectById(22L)).thenReturn(second);
        when(stock.restoreForAfterSale(eq(order), any())).thenReturn(Map.of());

        proxy.refundAfterSale(order, first, "退第一件");
        proxy.refundAfterSale(order, second, "退第二件");

        verify(balance).refund(eq(8L), eq(2_000L), eq("REFUND:MO-1:AS:21"), any());
        verify(balance).refund(eq(8L), eq(2_000L), eq("REFUND:MO-1:AS:22"), any());
        verify(wxpay, never()).refund(anyString(), anyString(), anyLong(), anyLong(), any());
    }

    private Refund refund() {
        Refund refund = new Refund();
        refund.setId(11L);
        refund.setRefundNo("RF-1");
        refund.setOrderId(1L);
        refund.setOrderNo("MO-1");
        refund.setAmount(2_000L);
        refund.setOriginalAmount(20_000L);
        refund.setStatus("REFUNDING");
        return refund;
    }

    private MallAfterSale afterSale() {
        MallAfterSale afterSale = new MallAfterSale();
        afterSale.setId(21L);
        afterSale.setOrderId(1L);
        afterSale.setOrderNo("MO-1");
        afterSale.setRefundAmount(2_000L);
        afterSale.setStatus("REFUNDING");
        afterSale.setItems("[{\"orderItemId\":31,\"quantity\":1}]");
        return afterSale;
    }

    private static class LocalTransactionManager extends AbstractPlatformTransactionManager {
        private Runnable beforeCommit = () -> { };
        private final ThreadLocal<LocalTransaction> current = new ThreadLocal<>();
        @Override protected Object doGetTransaction() {
            LocalTransaction transaction = current.get();
            return transaction == null ? new LocalTransaction() : transaction;
        }
        @Override protected boolean isExistingTransaction(Object transaction) {
            return ((LocalTransaction) transaction).active;
        }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((LocalTransaction) transaction).active = true;
            current.set((LocalTransaction) transaction);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { beforeCommit.run(); }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object transaction) { current.remove(); }
        private static class LocalTransaction { private boolean active; }
    }
}
