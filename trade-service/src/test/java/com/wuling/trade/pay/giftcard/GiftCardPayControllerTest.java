package com.wuling.trade.pay.giftcard;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.wuling.common.alert.AlertChannel;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.trade.entity.Payment;
import com.wuling.trade.mapper.PaymentMapper;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.port.UserQueryPort;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GiftCardPayControllerTest {

    private static final String ORDER_NO = "GC202609260001";
    private static final Long USER_ID = 7L;
    private static final Long AMOUNT = 8800L;

    private GiftCardOrderPort orderPort;
    private PaymentService paymentService;
    private PaymentGatewayResolver gatewayResolver;
    private UserQueryPort userQueryPort;
    private GiftCardRefundService refundService;
    private GiftCardPayController controller;

    @BeforeEach
    void setUp() {
        orderPort = mock(GiftCardOrderPort.class);
        paymentService = mock(PaymentService.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        userQueryPort = mock(UserQueryPort.class);
        refundService = mock(GiftCardRefundService.class);
        controller = new GiftCardPayController(
                orderPort, paymentService, gatewayResolver, userQueryPort, refundService);
        CurrentUser.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void mockPrepaySettlesExactlyOneServerPricedOrder() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order("UNPAID", "CREATED", "UNVERIFIED", null));
        when(gatewayResolver.isMockChannel()).thenReturn(true);

        Result<WxPayPrepayResult> result = controller.prepay(Map.of("orderNo", ORDER_NO));

        assertNull(result.getData());
        verify(orderPort).settle(ORDER_NO, "DEMO-" + ORDER_NO, null, AMOUNT);
        verifyNoInteractions(paymentService, userQueryPort);
    }

    @Test
    void prepayRejectsAnotherUsersOrder() {
        GiftCardOrderPort.GiftCardOrderView order = order("UNPAID", "CREATED", "UNVERIFIED", null);
        order.setUserId(99L);
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.prepay(Map.of("orderNo", ORDER_NO)));

        assertEquals(ResultCode.FORBIDDEN, ex.getCode());
        verify(orderPort, never()).settle(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void prepayRejectsPaidOrVerifiedTerminalOrder() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order("PAID", "PAID", "UNVERIFIED", "WX-1"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.prepay(Map.of("orderNo", ORDER_NO)));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verify(orderPort, never()).settle(anyString(), anyString(), any(), anyLong());
    }

    @Test
    void prepayUsesServerAmountAndOpenidForWechat() {
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(15);
        GiftCardOrderPort.GiftCardOrderView order = order("UNPAID", "CREATED", "UNVERIFIED", null);
        order.setExpireTime(expireTime);
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order);
        when(gatewayResolver.isMockChannel()).thenReturn(false);
        when(userQueryPort.findOpenid(USER_ID)).thenReturn("openid-7");
        WxPayPrepayResult expected = new WxPayPrepayResult();
        when(paymentService.prepayGiftCard(
                eq(ORDER_NO), eq(AMOUNT), eq("openid-7"), anyString(), eq(expireTime)))
                .thenReturn(expected);

        Result<WxPayPrepayResult> result = controller.prepay(Map.of("orderNo", ORDER_NO));

        assertSame(expected, result.getData());
        verify(paymentService).prepayGiftCard(
                ORDER_NO, AMOUNT, "openid-7", "礼品卡购买", expireTime);
    }

    @Test
    void prepayRejectsExpiredUnpaidOrderBeforeContactingWechat() {
        GiftCardOrderPort.GiftCardOrderView order = order("UNPAID", "CREATED", "UNVERIFIED", null);
        order.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.prepay(Map.of("orderNo", ORDER_NO)));

        assertEquals(ResultCode.BAD_REQUEST, ex.getCode());
        verifyNoInteractions(paymentService, userQueryPort);
    }

    @Test
    void refundDelegatesToGiftCardRefundService() {
        GiftCardRefundResult expected = new GiftCardRefundResult("GR1", AMOUNT, "REFUNDING");
        when(refundService.refund(ORDER_NO, USER_ID, "不喜欢")).thenReturn(expected);

        Result<GiftCardRefundResult> result = controller.refund(
                Map.of("orderNo", ORDER_NO, "reason", "不喜欢"));

        assertSame(expected, result.getData());
        verify(refundService).refund(ORDER_NO, USER_ID, "不喜欢");
    }

    private GiftCardOrderPort.GiftCardOrderView order(String payStatus, String status,
                                                       String verifyStatus, String transactionId) {
        GiftCardOrderPort.GiftCardOrderView order = new GiftCardOrderPort.GiftCardOrderView();
        order.setOrderNo(ORDER_NO);
        order.setUserId(USER_ID);
        order.setAmount(AMOUNT);
        order.setPayStatus(payStatus);
        order.setStatus(status);
        order.setVerifyStatus(verifyStatus);
        order.setTransactionId(transactionId);
        order.setExpireTime(LocalDateTime.now().plusMinutes(15));
        return order;
    }
}
