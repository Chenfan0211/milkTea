package com.wuling.trade.service;

import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.OrderRefundedEvent;
import com.wuling.common.outbox.OutboxService;
import com.wuling.trade.entity.MallAfterSale;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.OrderItem;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.MallAfterSaleMapper;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.mapper.RefundMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.port.SettlementQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceAfterSaleTest {

    private OrderMapper orderMapper;
    private OrderItemMapper orderItemMapper;
    private RefundMapper refundMapper;
    private MallAfterSaleMapper afterSaleMapper;
    private OrderService orderService;
    private SettlementQueryPort settlementQueryPort;
    private OutboxService outboxService;
    private MallStockService mallStockService;
    private PaymentGatewayResolver gatewayResolver;
    private RefundService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        refundMapper = mock(RefundMapper.class);
        afterSaleMapper = mock(MallAfterSaleMapper.class);
        orderService = mock(OrderService.class);
        settlementQueryPort = mock(SettlementQueryPort.class);
        outboxService = mock(OutboxService.class);
        mallStockService = mock(MallStockService.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        service = new RefundService(orderMapper, orderItemMapper, refundMapper,
                mock(PaymentMapper.class), settlementQueryPort, orderService,
                gatewayResolver, outboxService,
                mock(StoredValueBalancePort.class), mallStockService, afterSaleMapper);
    }

    @Test
    @DisplayName("部分售后成功保留订单发货状态并发布真实退款比例")
    void partialAfterSaleSuccessKeepsShippedAndPublishesRatio() {
        Order order = mallOrder(OrderService.STATUS_SHIPPED, 20_000L);
        Refund refund = afterSaleRefund(2_000L);
        MallAfterSale afterSale = afterSale();
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(2)));
        when(refundMapper.selectById(refund.getId())).thenReturn(refund);
        when(afterSaleMapper.selectById(401L)).thenReturn(afterSale);
        when(settlementQueryPort.query(order.getId())).thenReturn(settlement(true));
        when(mallStockService.restoreForAfterSale(order, afterSale)).thenReturn(Map.of(301L, 1));
        when(orderService.markAfterSaleRefunded(order, 1, 2)).thenReturn(false);

        service.markSuccessForTest(refund);

        verify(orderService).markAfterSaleRefunded(order, 1, 2);
        ArgumentCaptor<OrderRefundedEvent> event = ArgumentCaptor.forClass(OrderRefundedEvent.class);
        verify(outboxService).enqueue(eq("ORDER"), eq(order.getOrderNo()), eq("ORDER_REFUNDED"),
                eq(MqConstants.FINANCE_REVERSE_ROUTING_KEY), eq(order.getOrderNo()), event.capture());
        assertEquals(1_000, event.getValue().getRefundRatio());
        verify(outboxService, never()).enqueue(eq("ORDER"), anyString(),
                eq("COUPON_RESTORE_REFUND"), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("整笔售后成功后订单置已退款并恢复优惠券")
    void fullAfterSaleSuccessMovesOrderToRefundedAndRestoresCoupon() {
        Order order = mallOrder(OrderService.STATUS_COMPLETED, 20_000L);
        order.setCouponId(77L);
        Refund refund = afterSaleRefund(20_000L);
        MallAfterSale afterSale = afterSale();
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(2)));
        when(refundMapper.selectById(refund.getId())).thenReturn(refund);
        when(afterSaleMapper.selectById(401L)).thenReturn(afterSale);
        when(settlementQueryPort.query(order.getId())).thenReturn(settlement(false));
        when(mallStockService.restoreForAfterSale(order, afterSale)).thenReturn(Map.of(301L, 2));
        when(orderService.markAfterSaleRefunded(order, 2, 2)).thenReturn(true);

        service.markSuccessForTest(refund);

        verify(orderService).markAfterSaleRefunded(order, 2, 2);
        verify(outboxService).enqueue(eq("ORDER"), eq(order.getOrderNo()),
                eq("COUPON_RESTORE_REFUND"), eq(MqConstants.COUPON_EVENT_ROUTING_KEY),
                eq(order.getOrderNo()), any());
    }

    @Test
    @DisplayName("售后退款失败保持发货状态，不降级为待发货")
    void afterSaleFailureKeepsShippedStatus() {
        Order order = mallOrder(OrderService.STATUS_SHIPPED, 20_000L);
        Refund refund = afterSaleRefund(2_000L);
        when(orderMapper.selectById(order.getId())).thenReturn(order);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(orderItem(2)));
        when(afterSaleMapper.selectById(401L)).thenReturn(afterSale());

        service.markFailedForTest(refund, "微信退款失败");

        verify(orderService).markAfterSaleRefundFailed(order);
        verify(orderService, never()).markRefundFailed(order);
    }

    @Test
    @DisplayName("售后退款失败重试复用原退款单并回到退款中")
    void afterSaleRetryReusesOriginalRefundAndRestoresRefunding() {
        Order order = mallOrder(OrderService.STATUS_SHIPPED, 20_000L);
        Refund refund = afterSaleRefund(2_000L);
        refund.setStatus(RefundService.STATUS_FAILED);
        MallAfterSale afterSale = afterSale();
        afterSale.setStatus("FAILED");
        when(refundMapper.selectById(501L)).thenReturn(refund);
        when(orderMapper.selectById(200L)).thenReturn(order);
        when(afterSaleMapper.selectById(401L)).thenReturn(afterSale);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gatewayResolver.active()).thenReturn(gateway);
        when(gateway.refund(anyString(), anyString(), anyLong(), any())).thenReturn(new PaymentGateway.RefundApplyResult(true, "WX-RETRY", null));

        service.retry(501L);

        verify(afterSaleMapper).updateById(any(MallAfterSale.class));
        verify(orderService).markRefundPending(order);
        verify(refundMapper).updateById(refund);
        assertEquals(RefundService.STATUS_REFUNDING, refund.getStatus());
    }

    private OrderItem orderItem(int quantity) {
        OrderItem item = new OrderItem();
        item.setId(301L);
        item.setOrderId(200L);
        item.setMallSkuId(101L);
        item.setQuantity(quantity);
        return item;
    }

    private Order mallOrder(String status, long paidAmount) {
        Order order = new Order();
        order.setId(200L);
        order.setOrderNo("MO202610270001");
        order.setOrderType("MALL");
        order.setUserId(88L);
        order.setStatus(status);
        order.setPaidAmount(paidAmount);
        return order;
    }

    private Refund afterSaleRefund(long amount) {
        Refund refund = new Refund();
        refund.setId(501L);
        refund.setRefundNo("RF202610270001");
        refund.setOrderId(200L);
        refund.setOrderNo("MO202610270001");
        refund.setAfterSaleId(401L);
        refund.setAmount(amount);
        refund.setOriginalAmount(20_000L);
        refund.setStatus(RefundService.STATUS_REFUNDING);
        return refund;
    }

    private MallAfterSale afterSale() {
        MallAfterSale afterSale = new MallAfterSale();
        afterSale.setId(401L);
        afterSale.setOrderId(200L);
        afterSale.setStatus("REFUNDING");
        afterSale.setItems("[{\"orderItemId\":301,\"skuId\":101,\"quantity\":1}]");
        return afterSale;
    }

    private SettlementQueryPort.SettlementStatus settlement(boolean hasSettlement) {
        SettlementQueryPort.SettlementStatus status = new SettlementQueryPort.SettlementStatus();
        status.setHasSettlement(hasSettlement);
        return status;
    }
}

