package com.wuling.trade.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.giftcard.GiftCardOrderPort;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.pay.storedvalue.StoredValueOrderPort;
import com.wuling.trade.pay.wxpay.WxPayTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceOrderStatusTest {

    private static final String ORDER_NO = "O202609260001";
    private static final Long AMOUNT = 1000L;

    private OrderService orderService;
    private PaymentMapper paymentMapper;
    private OrderItemMapper orderItemMapper;
    private AlertChannel alertChannel;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        paymentMapper = mock(PaymentMapper.class);
        orderItemMapper = mock(OrderItemMapper.class);
        alertChannel = mock(AlertChannel.class);
        service = new PaymentService(
                orderService,
                paymentMapper,
                orderItemMapper,
                mock(PaymentGatewayResolver.class),
                alertChannel,
                mock(StoredValueOrderPort.class),
                mock(StoredValueBalancePort.class),
                mock(BalancePayIntentService.class),
                mock(com.wuling.common.mq.MqProducer.class),
                mock(GiftCardOrderPort.class));
        when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    }

    @Test
    void completedOrderWechatCallbackIsIdempotent() {
        Order order = order(OrderService.STATUS_COMPLETED, null);
        OrderDTO dto = new OrderDTO();
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);
        when(orderService.toDTO(eq(order), anyList())).thenReturn(dto);

        assertSame(dto, service.handleWxPayCallback(transaction()));

        verify(orderService, never()).markPaid(any(Order.class), any());
        verify(paymentMapper, never()).updateById(any(com.wuling.trade.entity.Payment.class));
        verify(alertChannel, never()).send(any(), any(), any(), any());
    }

    @Test
    void refundedOrderLateWechatCallbackIsIdempotent() {
        Order order = order(OrderService.STATUS_CANCELED, "REFUNDED");
        OrderDTO dto = new OrderDTO();
        when(orderService.lockForUpdate(ORDER_NO)).thenReturn(order);
        when(orderService.toDTO(eq(order), anyList())).thenReturn(dto);

        assertSame(dto, service.handleWxPayCallback(transaction()));

        verify(orderService, never()).markPaid(any(Order.class), any());
        verify(paymentMapper, never()).updateById(any(com.wuling.trade.entity.Payment.class));
        verify(alertChannel, never()).send(any(), any(), any(), any());
    }

    private Order order(String status, String refundStatus) {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo(ORDER_NO);
        order.setUserId(7L);
        order.setStoreSubjectId(9L);
        order.setPaidAmount(AMOUNT);
        order.setStatus(status);
        order.setRefundStatus(refundStatus);
        return order;
    }

    private WxPayTransaction transaction() {
        WxPayTransaction transaction = new WxPayTransaction();
        transaction.setOutTradeNo(ORDER_NO);
        transaction.setTransactionId("WX-TRANSACTION-1");
        transaction.setPayerOpenid("openid-7");
        transaction.setTotalAmount(AMOUNT);
        transaction.setNotifyId("NOTIFY-1");
        return transaction;
    }
}