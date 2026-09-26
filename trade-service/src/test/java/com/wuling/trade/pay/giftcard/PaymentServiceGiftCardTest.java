package com.wuling.trade.pay.giftcard;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.common.mq.MqProducer;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.OrderItemMapper;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.storedvalue.StoredValueBalancePort;
import com.wuling.trade.pay.storedvalue.StoredValueOrderPort;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.pay.wxpay.WxPayTransaction;
import com.wuling.trade.service.BalancePayIntentService;
import com.wuling.trade.service.OrderService;
import com.wuling.trade.service.PaymentGateway;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

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

class PaymentServiceGiftCardTest {

    private static final String ORDER_NO = "GC202609260001";
    private static final Long USER_ID = 7L;
    private static final Long AMOUNT = 8800L;

    private OrderService orderService;
    private PaymentMapper paymentMapper;
    private PaymentGatewayResolver gatewayResolver;
    private PaymentGateway paymentGateway;
    private AlertChannel alertChannel;
    private StoredValueOrderPort storedValueOrderPort;
    private GiftCardOrderPort giftCardOrderPort;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        paymentMapper = mock(PaymentMapper.class);
        OrderItemMapper orderItemMapper = mock(OrderItemMapper.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        paymentGateway = mock(PaymentGateway.class);
        alertChannel = mock(AlertChannel.class);
        storedValueOrderPort = mock(StoredValueOrderPort.class);
        StoredValueBalancePort storedValueBalancePort = mock(StoredValueBalancePort.class);
        BalancePayIntentService balancePayIntentService = mock(BalancePayIntentService.class);
        MqProducer mqProducer = mock(MqProducer.class);
        giftCardOrderPort = mock(GiftCardOrderPort.class);
        service = new PaymentService(
                orderService, paymentMapper, orderItemMapper, gatewayResolver, alertChannel,
                storedValueOrderPort, storedValueBalancePort, balancePayIntentService,
                mqProducer, giftCardOrderPort);
    }

    @Test
    void prepayWritesGiftCardPaymentRecordWithServerFields() {
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(15);
        WxPayPrepayResult prepayResult = new WxPayPrepayResult();
        prepayResult.setPrepayId("prepay-1");
        when(gatewayResolver.activeChannel()).thenReturn("WXPAY");
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.prepayForMiniApp(
                ORDER_NO, AMOUNT, "openid-7", "礼品卡购买", expireTime))
                .thenReturn(prepayResult);

        service.prepayGiftCard(ORDER_NO, AMOUNT, "openid-7", "礼品卡购买", expireTime);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).insert(captor.capture());
        Payment payment = captor.getValue();
        assertEquals(Payment.BIZ_GIFT_CARD, payment.getBizType());
        assertEquals("GCPAY-" + ORDER_NO, payment.getPaymentNo());
        assertEquals("WXPAY", payment.getChannel());
        assertEquals(ORDER_NO, payment.getOrderNo());
        assertEquals(ORDER_NO, payment.getBizNo());
        assertEquals(AMOUNT, payment.getAmount());
        assertEquals("openid-7", payment.getPayerOpenid());
        assertEquals("prepay-1", payment.getPrepayId());
        assertEquals("PENDING", payment.getThirdStatus());
        assertEquals("PAYING", payment.getStandardStatus());
    }

    @Test
    void prepayReusesExistingGiftCardPaymentRecord() {
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(15);
        WxPayPrepayResult prepayResult = new WxPayPrepayResult();
        prepayResult.setPrepayId("prepay-2");
        Payment existing = new Payment();
        existing.setId(11L);
        existing.setPaymentNo("PAY-EXISTING");
        existing.setOrderNo(ORDER_NO);
        existing.setBizType(Payment.BIZ_GIFT_CARD);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(gatewayResolver.activeChannel()).thenReturn("WXPAY");
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.prepayForMiniApp(
                ORDER_NO, AMOUNT, "openid-8", "礼品卡购买", expireTime))
                .thenReturn(prepayResult);

        service.prepayGiftCard(ORDER_NO, AMOUNT, "openid-8", "礼品卡购买", expireTime);

        verify(paymentMapper, never()).insert(any(Payment.class));
        verify(paymentMapper).updateById(existing);
        assertEquals("PAY-EXISTING", existing.getPaymentNo());
        assertEquals(AMOUNT, existing.getAmount());
        assertEquals("openid-8", existing.getPayerOpenid());
        assertEquals("prepay-2", existing.getPrepayId());
        assertEquals("PENDING", existing.getThirdStatus());
        assertEquals("PAYING", existing.getStandardStatus());
    }

    @Test
    void prepayRecoversConcurrentInsertAndKeepsSingleGiftCardPaymentRecord() {
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(15);
        WxPayPrepayResult prepayResult = new WxPayPrepayResult();
        prepayResult.setPrepayId("prepay-concurrent");
        Payment concurrent = new Payment();
        concurrent.setId(12L);
        concurrent.setPaymentNo("GCPAY-" + ORDER_NO);
        concurrent.setOrderNo(ORDER_NO);
        concurrent.setBizType(Payment.BIZ_GIFT_CARD);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null, concurrent);
        when(paymentMapper.insert(any(Payment.class)))
                .thenThrow(new DuplicateKeyException("duplicate payment_no"));
        when(gatewayResolver.activeChannel()).thenReturn("WXPAY");
        when(gatewayResolver.active()).thenReturn(paymentGateway);
        when(paymentGateway.prepayForMiniApp(
                ORDER_NO, AMOUNT, "openid-7", "礼品卡购买", expireTime))
                .thenReturn(prepayResult);

        service.prepayGiftCard(ORDER_NO, AMOUNT, "openid-7", "礼品卡购买", expireTime);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).updateById(captor.capture());
        Payment saved = captor.getValue();
        assertEquals(12L, saved.getId());
        assertEquals("GCPAY-" + ORDER_NO, saved.getPaymentNo());
        assertEquals("prepay-concurrent", saved.getPrepayId());
        assertEquals(Payment.BIZ_GIFT_CARD, saved.getBizType());
    }
    @Test
    void callbackAmountMismatchIsRejectedBeforeSettlement() {
        when(giftCardOrderPort.findByOrderNo(ORDER_NO)).thenReturn(order("UNPAID"));
        WxPayTransaction transaction = transaction(AMOUNT + 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.routeWxPayCallback(transaction));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(alertChannel).send(
                eq(AlertChannel.Level.CRITICAL), anyString(), anyString(), eq(ORDER_NO));
        verify(giftCardOrderPort, never()).settle(
                anyString(), anyString(), any(), any());
    }

    @Test
    void callbackSettlesGiftCardAndMarksPaymentPaid() {
        when(giftCardOrderPort.findByOrderNo(ORDER_NO)).thenReturn(order("UNPAID"));
        Payment payment = new Payment();
        payment.setId(11L);
        payment.setOrderNo(ORDER_NO);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        service.routeWxPayCallback(transaction(AMOUNT));

        verify(giftCardOrderPort).settle(
                ORDER_NO, "WX-TRANSACTION-1", "openid-7", AMOUNT);
        verify(paymentMapper).updateById(payment);
        assertEquals("SUCCESS", payment.getThirdStatus());
        assertEquals("PAID", payment.getStandardStatus());
        assertEquals("WX-TRANSACTION-1", payment.getTransactionId());
        assertEquals("openid-7", payment.getPayerOpenid());
    }

    @Test
    void duplicateCallbackRepairsPaymentWithoutSettlingTwice() {
        when(giftCardOrderPort.findByOrderNo(ORDER_NO))
                .thenReturn(order("UNPAID"), order("PAID"));
        Payment payment = new Payment();
        payment.setId(11L);
        payment.setOrderNo(ORDER_NO);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        service.routeWxPayCallback(transaction(AMOUNT));
        service.routeWxPayCallback(transaction(AMOUNT));

        verify(giftCardOrderPort, times(1)).settle(
                ORDER_NO, "WX-TRANSACTION-1", "openid-7", AMOUNT);
        verify(paymentMapper, times(2)).updateById(payment);
        assertEquals("PAID", payment.getStandardStatus());
        assertEquals("SUCCESS", payment.getThirdStatus());
        assertEquals("WX-TRANSACTION-1", payment.getTransactionId());
    }

    @Test
    void callbackWithoutTransactionIdIsRejectedBeforeSettlement() {
        WxPayTransaction transaction = transaction(AMOUNT);
        transaction.setTransactionId(" ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.routeWxPayCallback(transaction));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(giftCardOrderPort, never()).findByOrderNo(anyString());
        verify(giftCardOrderPort, never()).settle(
                anyString(), anyString(), any(), any());
    }

    @Test
    void refundedOrderCallbackRepairsPaymentWithoutReissuingCard() {
        GiftCardOrderPort.GiftCardOrderView refunded = order("REFUNDED");
        refunded.setStatus("CANCELED");
        refunded.setTransactionId("WX-TRANSACTION-1");
        when(giftCardOrderPort.findByOrderNo(ORDER_NO)).thenReturn(refunded);
        Payment payment = new Payment();
        payment.setId(11L);
        payment.setOrderNo(ORDER_NO);
        payment.setStandardStatus("REFUNDED");
        payment.setThirdStatus("REFUND");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        service.routeWxPayCallback(transaction(AMOUNT));

        verify(giftCardOrderPort, never()).settle(
                anyString(), anyString(), any(), any());
        verify(paymentMapper).updateById(payment);
        assertEquals("REFUNDED", payment.getStandardStatus());
        assertEquals("REFUND", payment.getThirdStatus());
        assertEquals("WX-TRANSACTION-1", payment.getTransactionId());
    }

    @Test
    void refundStatusRefundingCallbackKeepsPaymentRefundingAndDoesNotSettle() {
        GiftCardOrderPort.GiftCardOrderView refunding = order("PAID");
        refunding.setStatus("PAID");
        refunding.setRefundStatus("REFUNDING");
        when(giftCardOrderPort.findByOrderNo(ORDER_NO)).thenReturn(refunding);
        Payment payment = new Payment();
        payment.setId(11L);
        payment.setOrderNo(ORDER_NO);
        payment.setStandardStatus("PAID");
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(payment);

        service.routeWxPayCallback(transaction(AMOUNT));

        verify(giftCardOrderPort, never()).settle(
                anyString(), anyString(), any(), any());
        assertEquals("REFUNDING", payment.getStandardStatus());
        assertEquals("REFUNDING", payment.getThirdStatus());
    }

    @Test
    void statusRefundingWithoutRefundStatusDoesNotBlockSettlement() {
        GiftCardOrderPort.GiftCardOrderView order = order("UNPAID");
        order.setStatus("REFUNDING");
        order.setRefundStatus(null);
        when(giftCardOrderPort.findByOrderNo(ORDER_NO)).thenReturn(order);
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.routeWxPayCallback(transaction(AMOUNT));

        verify(giftCardOrderPort).settle(
                ORDER_NO, "WX-TRANSACTION-1", "openid-7", AMOUNT);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).insert(captor.capture());
        assertEquals("PAID", captor.getValue().getStandardStatus());
        assertEquals("SUCCESS", captor.getValue().getThirdStatus());
    }
    private GiftCardOrderPort.GiftCardOrderView order(String payStatus) {
        GiftCardOrderPort.GiftCardOrderView order = new GiftCardOrderPort.GiftCardOrderView();
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setAmount(AMOUNT);
        order.setPayStatus(payStatus);
        order.setStatus("PAID".equals(payStatus) ? "PAID" : "CREATED");
        order.setVerifyStatus("UNVERIFIED");
        return order;
    }

    private WxPayTransaction transaction(Long amount) {
        WxPayTransaction transaction = new WxPayTransaction();
        transaction.setOutTradeNo(ORDER_NO);
        transaction.setTransactionId("WX-TRANSACTION-1");
        transaction.setPayerOpenid("openid-7");
        transaction.setTotalAmount(amount);
        transaction.setNotifyId("NOTIFY-1");
        return transaction;
    }
}
