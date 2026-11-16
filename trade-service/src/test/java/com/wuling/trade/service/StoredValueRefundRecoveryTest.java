package com.wuling.trade.service;

import com.wuling.common.outbox.OutboxService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.wuling.trade.entity.*;
import com.wuling.trade.mapper.*;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.port.SettlementQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local rollback must not erase the independent marketing funds ledger. */
class StoredValueRefundRecoveryTest {
    private final Map<String, Long> remoteRefunds = new HashMap<>();
    private final RefundMapper refunds = mock(RefundMapper.class);
    private final MallStockService stock = mock(MallStockService.class);
    private Order persistedOrder;
    private Refund persistedRefund;
    private MallAfterSale persistedAfterSale;
    private RefundService service;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Refund.class);
        persistedOrder = new Order(); persistedOrder.setId(1L); persistedOrder.setOrderNo("MO-RECOVERY");
        persistedOrder.setOrderType("MALL"); persistedOrder.setStatus("PAID");
        persistedOrder.setUserId(9L); persistedOrder.setPaidAmount(1L);
        OrderMapper orders = mock(OrderMapper.class);
        OrderItemMapper items = mock(OrderItemMapper.class);
        PaymentMapper payments = mock(PaymentMapper.class);
        MallAfterSaleMapper afterSales = mock(MallAfterSaleMapper.class);
        OrderService orderService = mock(OrderService.class);
        when(orders.selectById(1L)).thenAnswer(call -> copy(persistedOrder, Order.class));
        when(orderService.lockForUpdate("MO-RECOVERY")).thenAnswer(call -> copy(persistedOrder, Order.class));
        doAnswer(call -> {
            Order order = call.getArgument(0); order.setRefundStatus("PENDING");
            persistedOrder.setRefundStatus("PENDING"); return null;
        }).when(orderService).markRefundPending(any());
        doAnswer(call -> {
            persistedOrder.setStatus("REFUNDED"); persistedOrder.setRefundStatus("REFUNDED"); return null;
        }).when(orderService).markRefunded(any());
        when(orderService.markAfterSaleRefunded(any(), anyInt(), anyInt())).thenAnswer(call -> {
            persistedOrder.setStatus("REFUNDED"); persistedOrder.setRefundStatus("REFUNDED"); return true;
        });
        when(refunds.insert(any(Refund.class))).thenAnswer(call -> {
            Refund refund = call.getArgument(0); refund.setId(11L);
            persistedRefund = copy(refund, Refund.class); return 1;
        });
        when(refunds.update(isNull(), any())).thenAnswer(call -> {
            persistedRefund.setStatus("SUCCESS"); return 1;
        });
        when(afterSales.selectById(anyLong())).thenAnswer(call -> copy(persistedAfterSale, MallAfterSale.class));
        when(afterSales.updateById(any(MallAfterSale.class))).thenAnswer(call -> {
            persistedAfterSale.setStatus(((MallAfterSale) call.getArgument(0)).getStatus()); return 1;
        });
        Payment payment = new Payment(); payment.setChannel("STORED_VALUE"); payment.setAmount(1L);
        payment.setOrderId(1L); payment.setOrderNo("MO-RECOVERY"); payment.setStandardStatus("PAID");
        when(payments.selectOne(any())).thenReturn(payment);
        OrderItem item = new OrderItem(); item.setId(31L); item.setOrderId(1L); item.setQuantity(1);
        when(items.selectList(any())).thenReturn(List.of(item));
        SettlementQueryPort settlement = id -> new SettlementQueryPort.SettlementStatus();
        StoredValueBalancePort marketing = new StoredValueBalancePort() {
            public DeductResult deduct(Long userId, long amount, String bizNo, Long subjectId) {
                throw new AssertionError("refund cannot deduct");
            }
            public boolean hasSuccessfulPay(String bizNo) { return true; }
            public void refund(Long userId, long amount, String bizNo, Long subjectId) {
                assertEquals(9L, userId);
                Long original = remoteRefunds.putIfAbsent(bizNo, amount);
                if (original != null) assertEquals(original.longValue(), amount);
            }
        };
        RefundService target = new RefundService(orders, items, refunds, payments, settlement,
                orderService, mock(PaymentGatewayResolver.class), mock(OutboxService.class), marketing, stock, afterSales);
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new LocalTransactions(), new AnnotationTransactionAttributeSource()));
        service = (RefundService) proxy.getProxy();
    }

    @Test void originalWholeOrderRefundRecoversAfterRemoteCreditThenLocalRollback() {
        doThrow(new IllegalStateException("local stock write failed")).doNothing().when(stock).restoreForRefund(any());
        assertThrows(IllegalStateException.class, () -> service.refund("MO-RECOVERY", "取消"));
        assertEquals("PAID", persistedOrder.getStatus());
        assertNull(persistedOrder.getRefundStatus()); assertNull(persistedRefund);
        assertEquals(Map.of("REFUND:MO-RECOVERY", 1L), remoteRefunds);

        service.refund("MO-RECOVERY", "取消");
        assertEquals("REFUNDED", persistedOrder.getStatus());
        assertEquals("SUCCESS", persistedRefund.getStatus());
        assertEquals(Map.of("REFUND:MO-RECOVERY", 1L), remoteRefunds);
    }

    @Test void sameAfterSaleRecoversAfterRemoteCreditThenLocalRollback() {
        persistedOrder.setStatus("SHIPPED");
        persistedAfterSale = new MallAfterSale(); persistedAfterSale.setId(21L);
        persistedAfterSale.setOrderId(1L); persistedAfterSale.setOrderNo("MO-RECOVERY");
        persistedAfterSale.setStatus("PENDING"); persistedAfterSale.setRefundAmount(1L);
        when(stock.restoreForAfterSale(any(), any())).thenThrow(new IllegalStateException("local stock write failed"))
                .thenReturn(Map.of(31L, 1));
        assertThrows(IllegalStateException.class, () -> service.refundAfterSale(
                copy(persistedOrder, Order.class), copy(persistedAfterSale, MallAfterSale.class), "退一件"));
        assertEquals("SHIPPED", persistedOrder.getStatus());
        assertNull(persistedOrder.getRefundStatus()); assertNull(persistedRefund);
        assertEquals("PENDING", persistedAfterSale.getStatus());
        assertEquals(Map.of("REFUND:MO-RECOVERY:AS:21", 1L), remoteRefunds);

        service.refundAfterSale(copy(persistedOrder, Order.class), copy(persistedAfterSale, MallAfterSale.class), "退一件");
        assertEquals("SUCCESS", persistedRefund.getStatus());
        assertEquals("SUCCESS", persistedAfterSale.getStatus());
        assertEquals("REFUNDED", persistedOrder.getStatus());
        assertEquals(Map.of("REFUND:MO-RECOVERY:AS:21", 1L), remoteRefunds);
    }

    private static <T> T copy(T source, Class<T> type) {
        if (source == null) return null;
        try { T result = type.getDeclaredConstructor().newInstance(); BeanUtils.copyProperties(source, result); return result; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private class LocalTransactions extends AbstractPlatformTransactionManager {
        private Order orderBefore; private Refund refundBefore; private MallAfterSale afterSaleBefore;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            orderBefore = copy(persistedOrder, Order.class); refundBefore = copy(persistedRefund, Refund.class);
            afterSaleBefore = copy(persistedAfterSale, MallAfterSale.class);
        }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) {
            persistedOrder = orderBefore; persistedRefund = refundBefore; persistedAfterSale = afterSaleBefore;
        }
    }
}
