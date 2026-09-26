package com.wuling.trade.pay.giftcard;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.service.PaymentGateway;
import com.wuling.trade.service.PaymentGatewayResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiftCardRefundServiceTest {

    private static final String ORDER_NO = "GC202609260001";
    private static final Long USER_ID = 7L;
    private static final Long AMOUNT = 8800L;

    private GiftCardOrderPort orderPort;
    private PaymentGatewayResolver gatewayResolver;
    private PaymentGateway paymentGateway;
    private PaymentMapper paymentMapper;
    private AlertChannel alertChannel;
    private GiftCardRefundService service;

    @BeforeEach
    void setUp() {
        orderPort = mock(GiftCardOrderPort.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        paymentGateway = mock(PaymentGateway.class);
        paymentMapper = mock(PaymentMapper.class);
        alertChannel = mock(AlertChannel.class);
        service = new GiftCardRefundService(
                orderPort, gatewayResolver, paymentMapper, alertChannel);
    }

    @Test
    void refundRejectsAnotherUsersOrder() {
        GiftCardOrderPort.GiftCardOrderView order = refundableOrder();
        order.setUserId(99L);
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(orderPort, never()).refundBegin(anyString(), anyString());
    }

    @Test
    void demoOrHistoricalOrderRequiresManualRefund() {
        GiftCardOrderPort.GiftCardOrderView order = refundableOrder();
        order.setTransactionId("DEMO-" + ORDER_NO);
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderPort, never()).refundBegin(anyString(), anyString());
    }

    @Test
    void acceptedRefundReturnsRefundingState() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment());
        when(gatewayResolver.isMockChannel()).thenReturn(false);
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(orderPort.refundBegin(ORDER_NO, "不需要了"))
                .thenReturn(new GiftCardRefundResult("GR202609260001", AMOUNT, "REFUNDING"));
        when(paymentGateway.refund(
                ORDER_NO, "GR202609260001", AMOUNT, "不需要了"))
                .thenReturn(new PaymentGateway.RefundApplyResult(true, "WXR1", null));

        GiftCardRefundResult result = service.refund(ORDER_NO, USER_ID, "不需要了");

        assertEquals(new GiftCardRefundResult(
                "GR202609260001", AMOUNT, "REFUNDING"), result);
        verify(orderPort, never()).refundFail(anyString(), anyString(), anyString());

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).update(captor.capture(), any(Wrapper.class));
        assertEquals("REFUNDING", captor.getValue().getStandardStatus());
        assertEquals("REFUNDING", captor.getValue().getThirdStatus());
    }

    @Test
    void synchronousGatewayFailureRestoresOrderAndThrows() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment());
        when(gatewayResolver.isMockChannel()).thenReturn(false);
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(orderPort.refundBegin(ORDER_NO, "不需要了"))
                .thenReturn(new GiftCardRefundResult("GR202609260001", AMOUNT, "REFUNDING"));
        when(paymentGateway.refund(
                ORDER_NO, "GR202609260001", AMOUNT, "不需要了"))
                .thenReturn(new PaymentGateway.RefundApplyResult(false, null, "签名错误"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.ERROR, ex.getCode());
        verify(orderPort).refundFail(
                ORDER_NO, "GR202609260001", "签名错误");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper, times(2)).update(captor.capture(), any(Wrapper.class));
        Payment last = captor.getAllValues().get(1);
        assertEquals("PAID", last.getStandardStatus());
        assertEquals("SUCCESS", last.getThirdStatus());
        verify(alertChannel, never()).send(any(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownGatewayFailureKeepsRefundingAndAlerts() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment());
        when(gatewayResolver.isMockChannel()).thenReturn(false);
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(orderPort.refundBegin(ORDER_NO, "不需要了"))
                .thenReturn(new GiftCardRefundResult("GR202609260001", AMOUNT, "REFUNDING"));
        when(paymentGateway.refund(
                ORDER_NO, "GR202609260001", AMOUNT, "不需要了"))
                .thenThrow(new RuntimeException("gateway timeout"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.ERROR, ex.getCode());
        assertEquals("退款受理结果未知，请稍后查询订单状态再重试", ex.getMessage());
        verify(orderPort, never()).refundFail(anyString(), anyString(), anyString());

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).update(captor.capture(), any(Wrapper.class));
        assertEquals("REFUNDING", captor.getValue().getStandardStatus());
        assertEquals("REFUNDING", captor.getValue().getThirdStatus());
        verify(alertChannel).send(
                eq(AlertChannel.Level.CRITICAL), anyString(), anyString(), eq(ORDER_NO));
    }

    @Test
    void mockRefundCompletesImmediatelyAndMarksPaymentRefunded() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment());
        when(gatewayResolver.isMockChannel()).thenReturn(true);
        when(orderPort.refundBegin(ORDER_NO, "不需要了"))
                .thenReturn(new GiftCardRefundResult("GR202609260001", AMOUNT, "REFUNDING"));

        GiftCardRefundResult result = service.refund(ORDER_NO, USER_ID, "不需要了");

        assertEquals("SUCCESS", result.status());
        verify(orderPort).refundConfirm(ORDER_NO, "GR202609260001", null);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper, times(2)).update(captor.capture(), any(Wrapper.class));
        Payment last = captor.getAllValues().get(1);
        assertEquals("REFUNDED", last.getStandardStatus());
        assertEquals("REFUND", last.getThirdStatus());
    }

    @Test
    void refundCallbackSynchronizesPaymentTerminalStatus() {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment());

        service.onRefundResult(ORDER_NO, "GR202609260001", true, "WXR1", null);

        ArgumentCaptor<Payment> success = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).update(success.capture(), any(Wrapper.class));
        assertEquals("REFUNDED", success.getValue().getStandardStatus());
        assertEquals("REFUND", success.getValue().getThirdStatus());

        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment());
        service.onRefundResult(ORDER_NO, "GR202609260002", false, null, "ABNORMAL");

        ArgumentCaptor<Payment> failure = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper, times(2)).update(failure.capture(), any(Wrapper.class));
        Payment last = failure.getAllValues().get(1);
        assertEquals("PAID", last.getStandardStatus());
        assertEquals("SUCCESS", last.getThirdStatus());
    }

    @Test
    void missingPaymentRecordBlocksRefundBeforeRemoteStateChange() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderPort, never()).refundBegin(anyString(), anyString());
    }

    @Test
    void paymentMustBePaidBeforeRefund() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        Payment payment = payment();
        payment.setStandardStatus("PAYING");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderPort, never()).refundBegin(anyString(), anyString());
    }

    @Test
    void paymentAmountMustMatchOrderBeforeRefund() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        Payment payment = payment();
        payment.setAmount(AMOUNT + 1);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderPort, never()).refundBegin(anyString(), anyString());
    }

    @Test
    void paymentTransactionMustMatchOrderBeforeRefund() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(refundableOrder());
        Payment payment = payment();
        payment.setTransactionId("WX-TRANSACTION-OTHER");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.refund(ORDER_NO, USER_ID, "不需要了"));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderPort, never()).refundBegin(anyString(), anyString());
    }

    private GiftCardOrderPort.GiftCardOrderView refundableOrder() {
        GiftCardOrderPort.GiftCardOrderView order = new GiftCardOrderPort.GiftCardOrderView();
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setAmount(AMOUNT);
        order.setPayStatus("PAID");
        order.setStatus("PAID");
        order.setVerifyStatus("UNVERIFIED");
        order.setTransactionId("WX-TRANSACTION-1");
        return order;
    }

    private Payment payment() {
        Payment payment = new Payment();
        payment.setId(11L);
        payment.setBizType(Payment.BIZ_GIFT_CARD);
        payment.setOrderNo(ORDER_NO);
        payment.setAmount(AMOUNT);
        payment.setTransactionId("WX-TRANSACTION-1");
        payment.setStandardStatus("PAID");
        payment.setThirdStatus("SUCCESS");
        return payment;
    }
}
