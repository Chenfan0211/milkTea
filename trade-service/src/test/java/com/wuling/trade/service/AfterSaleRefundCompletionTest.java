package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.entity.*;
import com.wuling.trade.mapper.*;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.port.MallCatalogPort;
import com.wuling.trade.port.SettlementQueryPort;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Uses real stock aggregation and order projection, with SQL/remote boundaries represented in memory. */
class AfterSaleRefundCompletionTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderItemMapper items = mock(OrderItemMapper.class);
    private final RefundMapper refunds = mock(RefundMapper.class);
    private final MallAfterSaleMapper afterSales = mock(MallAfterSaleMapper.class);
    private final PaymentMapper payments = mock(PaymentMapper.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final StoredValueBalancePort balance = mock(StoredValueBalancePort.class);
    private final MallCatalogPort catalog = mock(MallCatalogPort.class);
    private final List<Refund> refundRows = new ArrayList<>();
    private final Map<Long, MallAfterSale> saleRows = new LinkedHashMap<>();
    private final Map<Long, Map<Long, Integer>> saleQuantities = new HashMap<>();
    private final Set<String> restoredBusinessNumbers = new HashSet<>();
    private final Map<String, Long> refundBusinessNumbers = new HashMap<>();
    private final List<OrderItem> itemRows = new ArrayList<>();
    private Order order;
    private Refund currentRefund;
    private RefundService service;
    private int restoredQuantity;

    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Refund.class);
        order = new Order(); order.setId(1L); order.setOrderNo("MO-CUMULATIVE");
        order.setOrderType("MALL"); order.setStatus("SHIPPED"); order.setPayStatus("PAID");
        order.setPaidAmount(2L); order.setUserId(9L);
        item(31L, 2);
        when(orders.selectOne(any())).thenReturn(order);
        when(orders.selectById(1L)).thenReturn(order);
        when(orders.updateById(any(Order.class))).thenAnswer(call -> {
            Order patch = call.getArgument(0);
            if (patch.getStatus() != null) order.setStatus(patch.getStatus());
            if (patch.getRefundStatus() != null) order.setRefundStatus(patch.getRefundStatus());
            return 1;
        });
        when(items.selectList(any())).thenAnswer(call -> List.copyOf(itemRows));
        when(items.selectById(anyLong())).thenAnswer(call -> itemRows.stream()
                .filter(item -> item.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(afterSales.selectById(anyLong())).thenAnswer(call -> saleRows.get(call.getArgument(0)));
        when(afterSales.updateById(any(MallAfterSale.class))).thenAnswer(call -> {
            MallAfterSale patch = call.getArgument(0);
            saleRows.get(patch.getId()).setStatus(patch.getStatus()); return 1;
        });
        when(afterSales.selectSumRefundedQuantity(eq(1L), anyLong())).thenAnswer(call -> saleRows.values().stream()
                .filter(row -> List.of("APPROVED", "REFUNDING", "SUCCESS").contains(row.getStatus()))
                .mapToInt(row -> saleQuantities.get(row.getId()).getOrDefault(call.getArgument(1), 0)).sum());
        when(refunds.insert(any(Refund.class))).thenAnswer(call -> {
            currentRefund = call.getArgument(0); currentRefund.setId((long) refundRows.size() + 100L);
            refundRows.add(currentRefund); return 1;
        });
        when(refunds.selectOne(any())).thenAnswer(call -> currentRefund);
        when(refunds.selectList(any())).thenAnswer(call -> refundRows.stream()
                .filter(row -> "SUCCESS".equals(row.getStatus())).toList());
        when(refunds.update(isNull(), any())).thenAnswer(call -> {
            if (!"REFUNDING".equals(currentRefund.getStatus())) return 0;
            currentRefund.setStatus("SUCCESS"); return 1;
        });
        Payment payment = new Payment(); payment.setId(5L); payment.setOrderId(1L);
        payment.setOrderNo(order.getOrderNo()); payment.setChannel("STORED_VALUE");
        payment.setAmount(2L); payment.setStandardStatus("PAID");
        when(payments.selectOne(any())).thenReturn(payment);
        when(catalog.restore(eq(order.getOrderNo()), anyString(), anyList())).thenAnswer(call -> {
            if (restoredBusinessNumbers.add(call.getArgument(1))) {
                List<MallCatalogPort.StockLine> lines = call.getArgument(2);
                restoredQuantity += lines.stream().mapToInt(MallCatalogPort.StockLine::quantity).sum();
            }
            return MallCatalogPort.StockResult.ok();
        });
        doAnswer(call -> {
            String businessNo = call.getArgument(2); long amount = call.getArgument(1);
            Long previous = refundBusinessNumbers.putIfAbsent(businessNo, amount);
            if (previous != null) assertEquals(previous.longValue(), amount); return null;
        }).when(balance).refund(anyLong(), anyLong(), anyString(), any());
        MallStockService stock = new MallStockService(items, catalog, afterSales);
        OrderService orderService = new OrderService(orders, items, null, null, null, null, outbox, null, stock);
        service = new RefundService(orders, items, refunds, payments,
                id -> new SettlementQueryPort.SettlementStatus(), orderService,
                mock(PaymentGatewayResolver.class), outbox, balance, stock, afterSales);
    }

    @Test void twoSynchronousStoredValueRefundsFinishTheOrderOnTheSecondItem() {
        stored(sale(21L, "PENDING", 1L, Map.of(31L, 1)));
        assertEquals("SHIPPED", order.getStatus());
        stored(sale(22L, "PENDING", 1L, Map.of(31L, 1)));
        assertEquals("REFUNDED", order.getStatus());
        assertEquals("PAID", order.getPayStatus());
        assertEquals(2, restoredQuantity);
        assertEquals(2L, refundBusinessNumbers.values().stream().mapToLong(Long::longValue).sum());
    }

    @Test void asynchronousRefundingRowsAreCountedOnceAcrossTwoCallbacks() {
        MallAfterSale first = sale(21L, "REFUNDING", 1L, Map.of(31L, 1));
        pendingRefund(first); service.onRefundResult(currentRefund.getRefundNo(), true, null);
        assertEquals("SHIPPED", order.getStatus());
        MallAfterSale second = sale(22L, "REFUNDING", 1L, Map.of(31L, 1));
        pendingRefund(second); service.onRefundResult(currentRefund.getRefundNo(), true, null);
        assertEquals("REFUNDED", order.getStatus());
        service.onRefundResult(currentRefund.getRefundNo(), true, null);
        assertEquals(2, restoredQuantity);
        assertTrue(refundBusinessNumbers.isEmpty());
    }

    @Test void multipleItemRefundsAccumulatePerItemAndRepeatedCallbackDoesNotRestoreTwice() {
        item(32L, 1);
        stored(sale(21L, "PENDING", 1L, Map.of(31L, 1, 32L, 1)));
        assertEquals("SHIPPED", order.getStatus());
        stored(sale(22L, "PENDING", 1L, Map.of(31L, 1)));
        assertEquals("REFUNDED", order.getStatus());
        service.onRefundResult(currentRefund.getRefundNo(), true, null);
        assertEquals(3, restoredQuantity);
        assertEquals(2, refundBusinessNumbers.size());
    }

    @Test void originalSuccessfulRefundQueryRepairsOnlyTheOrderProjection() {
        successful(sale(21L, "SUCCESS", 1L, Map.of(31L, 1)));
        successful(sale(22L, "SUCCESS", 1L, Map.of(31L, 1)));
        assertTrue(service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("REFUNDED", order.getStatus());
        assertEquals("PAID", order.getPayStatus());
        assertFalse(service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void successfulQueryDoesNotCountAnotherRefundStillInFlight() {
        successful(sale(21L, "SUCCESS", 1L, Map.of(31L, 1)));
        sale(22L, "REFUNDING", 1L, Map.of(31L, 1));
        assertFalse(service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("SHIPPED", order.getStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void projectionRepairRejectsSuccessfulRefundsExceedingOriginalPayment() {
        successful(sale(21L, "SUCCESS", 2L, Map.of(31L, 1)));
        successful(sale(22L, "SUCCESS", 1L, Map.of(31L, 1)));
        assertThrows(BusinessException.class,
                () -> service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("SHIPPED", order.getStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void projectionRepairRejectsAfterSaleBelongingToAnotherOrder() {
        MallAfterSale first = sale(21L, "SUCCESS", 2L, Map.of(31L, 2));
        successful(first); first.setOrderId(2L);
        assertThrows(BusinessException.class,
                () -> service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("SHIPPED", order.getStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void completedOrderCanRepairFullRefundWithoutChangingPaidFact() {
        order.setStatus("COMPLETED");
        successful(sale(21L, "SUCCESS", 2L, Map.of(31L, 2)));
        assertTrue(service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("REFUNDED", order.getStatus());
        assertEquals("PAID", order.getPayStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void allItemsReturnedWithoutFullAmountDoesNotProjectFullRefund() {
        successful(sale(21L, "SUCCESS", 1L, Map.of(31L, 2)));
        assertFalse(service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("SHIPPED", order.getStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void projectionRepairRejectsQuantityAboveOriginalItemSnapshot() {
        successful(sale(21L, "SUCCESS", 2L, Map.of(31L, 3)));
        assertThrows(BusinessException.class,
                () -> service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("SHIPPED", order.getStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    @Test void projectionRepairRejectsRefundBelongingToAnotherOrder() {
        successful(sale(21L, "SUCCESS", 2L, Map.of(31L, 2)));
        currentRefund.setOrderId(2L);
        assertThrows(BusinessException.class,
                () -> service.syncRefundStatus(order.getOrderNo(), currentRefund.getRefundNo()));
        assertEquals("SHIPPED", order.getStatus());
        verifyNoInteractions(balance, catalog, outbox);
    }

    private void item(long id, int quantity) {
        OrderItem item = new OrderItem(); item.setId(id); item.setOrderId(1L);
        item.setMallSkuId(id + 100L); item.setQuantity(quantity); itemRows.add(item);
    }

    private MallAfterSale sale(long id, String status, long amount, Map<Long, Integer> quantities) {
        MallAfterSale sale = new MallAfterSale(); sale.setId(id); sale.setOrderId(1L);
        sale.setOrderNo(order.getOrderNo()); sale.setAfterSaleNo("AS-" + id);
        sale.setStatus(status); sale.setRefundAmount(amount);
        sale.setItems(quantities.entrySet().stream().map(entry -> "{\"orderItemId\":" + entry.getKey()
                + ",\"quantity\":" + entry.getValue() + "}").collect(java.util.stream.Collectors.joining(",", "[", "]")));
        saleRows.put(id, sale); saleQuantities.put(id, quantities); return sale;
    }

    private void stored(MallAfterSale sale) {
        Refund refund = service.refundAfterSale(order, sale, "退货"); sale.setRefundId(refund.getId());
    }

    private void pendingRefund(MallAfterSale sale) {
        Refund refund = new Refund(); refund.setId((long) refundRows.size() + 100L);
        refund.setOrderId(1L); refund.setOrderNo(order.getOrderNo()); refund.setAfterSaleId(sale.getId());
        refund.setRefundNo("RF-" + sale.getId()); refund.setAmount(sale.getRefundAmount());
        refund.setOriginalAmount(2L); refund.setStatus("REFUNDING");
        sale.setRefundId(refund.getId()); refundRows.add(refund); currentRefund = refund;
    }

    private void successful(MallAfterSale sale) { pendingRefund(sale); currentRefund.setStatus("SUCCESS"); }
}
