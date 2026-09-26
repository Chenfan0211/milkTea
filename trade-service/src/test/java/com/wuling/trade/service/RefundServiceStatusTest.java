package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.Order;
import com.wuling.trade.entity.Refund;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.OrderMapper;
import com.wuling.trade.mapper.RefundMapper;
import com.wuling.trade.port.SettlementQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefundServiceStatusTest {

    private static final String ORDER_NO = "O202609260001";
    private static final String REFUND_NO = "RF202609260001";
    private static final Long ORDER_ID = 1L;
    private static final Long REFUND_ID = 11L;
    private static final Long AMOUNT = 1000L;

    private OrderMapper orderMapper;
    private RefundMapper refundMapper;
    private SettlementQueryPort settlementQueryPort;
    private OrderService orderService;
    private PaymentGatewayResolver gatewayResolver;
    private PaymentGateway paymentGateway;
    private RefundService service;

    @BeforeEach
    void setUp() {
        orderMapper = mock(OrderMapper.class);
        refundMapper = mock(RefundMapper.class);
        settlementQueryPort = mock(SettlementQueryPort.class);
        orderService = mock(OrderService.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        paymentGateway = mock(PaymentGateway.class);
        service = new RefundService(
                orderMapper,
                mock(OrderItemMapper.class),
                refundMapper,
                settlementQueryPort,
                orderService,
                gatewayResolver,
                mock(com.wuling.common.mq.MqProducer.class));
    }

    @Test
    void paidOrderCanBeAcceptedForRefund() {
        Order order = order(OrderService.STATUS_PAID, null);
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);
        when(settlementQueryPort.query(ORDER_ID)).thenReturn(settlement(false, false));
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.refund(eq(ORDER_NO), anyString(), eq(AMOUNT), eq("user cancel")))
                .thenReturn(null);
        doAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            refund.setId(REFUND_ID);
            return 1;
        }).when(refundMapper).insert(any(Refund.class));

        service.refund(ORDER_NO, "user cancel");

        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).insert(captor.capture());
        assertEquals(RefundService.STATUS_REFUNDING, captor.getValue().getStatus());
        assertEquals(OrderService.STATUS_PAID, order.getStatus());
        verify(orderService).markRefundPending(order);
    }

    @Test
    void completedOrderCanBeAcceptedForRefund() {
        Order order = order(OrderService.STATUS_COMPLETED, null);
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.refund(eq(ORDER_NO), anyString(), eq(AMOUNT), eq("after verify")))
                .thenReturn(null);
        doAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            refund.setId(REFUND_ID);
            return 1;
        }).when(refundMapper).insert(any(Refund.class));

        service.refund(ORDER_NO, "after verify");

        assertEquals(OrderService.STATUS_COMPLETED, order.getStatus());
        verify(orderService).markRefundPending(order);
        verify(refundMapper).insert(any(Refund.class));
    }

    @Test
    void legacyVerifiedOrderCannotRefund() {
        assertRefundRejected("VERIFIED", null);
    }

    @Test
    void canceledOrderCannotRefund() {
        assertRefundRejected(OrderService.STATUS_CANCELED, "REFUNDED");
    }

    @Test
    void refundPendingCannotBeAppliedAgain() {
        assertRefundRejected(OrderService.STATUS_PAID, "PENDING");
    }

    @Test
    void failedGatewayMarksOrderRefundFailedAndKeepsPaid() {
        Order order = order(OrderService.STATUS_PAID, null);
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);
        when(settlementQueryPort.query(ORDER_ID)).thenReturn(settlement(false, false));
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.refund(eq(ORDER_NO), anyString(), eq(AMOUNT), any()))
                .thenReturn(new PaymentGateway.RefundApplyResult(false, null, "declined"));
        doAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            refund.setId(REFUND_ID);
            return 1;
        }).when(refundMapper).insert(any(Refund.class));

        service.refund(ORDER_NO, "user cancel");

        verify(orderService).markRefundFailed(order);
        assertEquals(OrderService.STATUS_PAID, order.getStatus());
    }

    @Test
    void failedGatewayKeepsCompletedOrderCompleted() {
        Order order = order(OrderService.STATUS_COMPLETED, null);
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.refund(eq(ORDER_NO), anyString(), eq(AMOUNT), any()))
                .thenReturn(new PaymentGateway.RefundApplyResult(false, null, "declined"));
        doAnswer(invocation -> {
            Refund refund = invocation.getArgument(0);
            refund.setId(REFUND_ID);
            return 1;
        }).when(refundMapper).insert(any(Refund.class));

        service.refund(ORDER_NO, "after verify");

        verify(orderService).markRefundFailed(order);
        assertEquals(OrderService.STATUS_COMPLETED, order.getStatus());
    }

    @Test
    void successfulRefundResultMarksOrderRefunded() {
        Refund refund = refund(RefundService.STATUS_REFUNDING);
        Order order = order(OrderService.STATUS_PAID, "PENDING");
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(settlementQueryPort.query(ORDER_ID)).thenReturn(settlement(false, false));

        service.onRefundResult(REFUND_NO, true, null);

        verify(orderService).markRefunded(order);
    }

    @Test
    void failedRefundResultMarksOrderRefundFailed() {
        Refund refund = refund(RefundService.STATUS_REFUNDING);
        Order order = order(OrderService.STATUS_PAID, "PENDING");
        when(refundMapper.selectOne(any(Wrapper.class))).thenReturn(refund);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);

        service.onRefundResult(REFUND_NO, false, "closed");

        verify(orderService).markRefundFailed(order);
    }

    @Test
    void retryMarksOrderRefundPendingAgain() {
        Refund refund = refund(RefundService.STATUS_FAILED);
        Order order = order(OrderService.STATUS_PAID, "FAILED");
        when(refundMapper.selectById(REFUND_ID)).thenReturn(refund);
        when(orderMapper.selectById(ORDER_ID)).thenReturn(order);
        when(settlementQueryPort.query(ORDER_ID)).thenReturn(settlement(false, false));
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.refund(eq(ORDER_NO), anyString(), eq(AMOUNT), any()))
                .thenReturn(null);

        service.retry(REFUND_ID);

        verify(orderService).markRefundPending(order);
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        verify(refundMapper).updateById(captor.capture());
        assertEquals(RefundService.STATUS_REFUNDING, captor.getValue().getStatus());
    }

    private void assertRefundRejected(String status, String refundStatus) {
        Order order = order(status, refundStatus);
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(
                BusinessException.class, () -> service.refund(ORDER_NO, "test"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderService, never()).markRefundPending(any(Order.class));
        verify(refundMapper, never()).insert(any(Refund.class));
    }

    private Order order(String status, String refundStatus) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setStoreSubjectId(9L);
        order.setPaidAmount(AMOUNT);
        order.setStatus(status);
        order.setRefundStatus(refundStatus);
        return order;
    }

    private Refund refund(String status) {
        Refund refund = new Refund();
        refund.setId(REFUND_ID);
        refund.setRefundNo(REFUND_NO);
        refund.setOrderId(ORDER_ID);
        refund.setOrderNo(ORDER_NO);
        refund.setAmount(AMOUNT);
        refund.setStatus(status);
        return refund;
    }

    private SettlementQueryPort.SettlementStatus settlement(boolean settled, boolean hasSettlement) {
        SettlementQueryPort.SettlementStatus status = new SettlementQueryPort.SettlementStatus();
        status.setSettled(settled);
        status.setHasSettlement(hasSettlement);
        return status;
    }
}
