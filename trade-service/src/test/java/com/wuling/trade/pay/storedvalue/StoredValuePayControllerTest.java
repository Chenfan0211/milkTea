package com.wuling.trade.pay.storedvalue;

import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.CurrentUser;
import com.wuling.trade.pay.wxpay.WxPayPrepayResult;
import com.wuling.trade.port.UserQueryPort;
import com.wuling.trade.service.PaymentGatewayResolver;
import com.wuling.trade.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValuePayControllerTest {

    private static final String ORDER_NO = "CZ202609260001";
    private static final Long USER_ID = 7L;
    private static final Long AMOUNT = 50000L;

    private StoredValueOrderPort orderPort;
    private PaymentService paymentService;
    private PaymentGatewayResolver gatewayResolver;
    private UserQueryPort userQueryPort;

    @BeforeEach
    void setUp() {
        orderPort = mock(StoredValueOrderPort.class);
        paymentService = mock(PaymentService.class);
        gatewayResolver = mock(PaymentGatewayResolver.class);
        userQueryPort = mock(UserQueryPort.class);
        CurrentUser.set(USER_ID);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void demoMockMarksPaidAndReturnsEmptyPaymentParams() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order(USER_ID, AMOUNT, "UNPAID"));
        when(gatewayResolver.isMockChannel()).thenReturn(true);

        Result<WxPayPrepayResult> result = controller(true).prepay(Map.of("orderNo", ORDER_NO));

        assertNull(result.getData(), "模拟充值不产生真实支付参数");
        verify(orderPort).markPaid(ORDER_NO, "DEMO-" + ORDER_NO, null, AMOUNT);
    }

    @Test
    void alreadyPaidDemoOrderReturnsSuccessWithoutMarkingPaidAgain() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order(USER_ID, AMOUNT, "PAID"));
        when(gatewayResolver.isMockChannel()).thenReturn(true);

        Result<WxPayPrepayResult> result = controller(true).prepay(Map.of("orderNo", ORDER_NO));

        assertNull(result.getData());
        verify(orderPort, never()).markPaid(ORDER_NO, "DEMO-" + ORDER_NO, null, AMOUNT);
    }

    @Test
    void disabledDemoSwitchKeepsExistingMockBehaviorWithoutMarkingPaid() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order(USER_ID, AMOUNT, "UNPAID"));
        when(gatewayResolver.isMockChannel()).thenReturn(true);

        Result<WxPayPrepayResult> result = controller(false).prepay(Map.of("orderNo", ORDER_NO));

        assertNull(result.getData());
        verify(orderPort, never()).markPaid(ORDER_NO, "DEMO-" + ORDER_NO, null, AMOUNT);
    }

    @Test
    void wxpayChannelKeepsExistingPrepayFlowEvenWhenDemoSwitchIsEnabled() {
        WxPayPrepayResult prepayResult = new WxPayPrepayResult();
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order(USER_ID, AMOUNT, "UNPAID"));
        when(gatewayResolver.isMockChannel()).thenReturn(false);
        when(userQueryPort.findOpenid(USER_ID)).thenReturn("openid-7");
        when(paymentService.prepayStoredValue(ORDER_NO, AMOUNT, "openid-7", "会员储值"))
                .thenReturn(prepayResult);

        Result<WxPayPrepayResult> result = controller(true).prepay(Map.of("orderNo", ORDER_NO));

        assertSame(prepayResult, result.getData());
        verify(orderPort, never()).markPaid(ORDER_NO, "DEMO-" + ORDER_NO, null, AMOUNT);
        verify(paymentService).prepayStoredValue(ORDER_NO, AMOUNT, "openid-7", "会员储值");
    }

    @Test
    void otherUsersOrderCannotBeSimulated() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order(8L, AMOUNT, "UNPAID"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller(true).prepay(Map.of("orderNo", ORDER_NO)));

        assertEquals(ResultCode.FORBIDDEN, exception.getCode());
        verify(orderPort, never()).markPaid(ORDER_NO, "DEMO-" + ORDER_NO, null, AMOUNT);
    }

    @Test
    void invalidAmountCannotBeSimulated() {
        when(orderPort.findByOrderNo(ORDER_NO)).thenReturn(order(USER_ID, 0L, "UNPAID"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller(true).prepay(Map.of("orderNo", ORDER_NO)));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        verify(orderPort, never()).markPaid(ORDER_NO, "DEMO-" + ORDER_NO, null, 0L);
    }

    private StoredValuePayController controller(boolean demoEnabled) {
        return new StoredValuePayController(
                orderPort, paymentService, gatewayResolver, userQueryPort, demoEnabled);
    }

    private StoredValueOrderPort.StoredValueOrderView order(Long userId, Long amount, String status) {
        StoredValueOrderPort.StoredValueOrderView order =
                new StoredValueOrderPort.StoredValueOrderView();
        order.setOrderNo(ORDER_NO);
        order.setUserId(userId);
        order.setAmount(amount);
        order.setPayStatus(status);
        return order;
    }
}
