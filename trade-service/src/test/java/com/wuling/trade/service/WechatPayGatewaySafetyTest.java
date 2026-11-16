package com.wuling.trade.service;

import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest;
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.model.TransactionAmount;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WechatPayGatewaySafetyTest {
    private JsapiServiceExtension jsapi;
    private com.wechat.pay.java.service.refund.RefundService refunds;
    private WechatPayGateway gateway;
    private Transaction transaction;

    @BeforeEach void setup() {
        WxPayProperties properties = new WxPayProperties();
        properties.setMchId("test-mch"); properties.setAppId("test-app");
        jsapi = mock(JsapiServiceExtension.class);
        refunds = mock(com.wechat.pay.java.service.refund.RefundService.class);
        gateway = new WechatPayGateway(properties, jsapi, refunds);
        transaction = new Transaction(); transaction.setOutTradeNo("MO-1");
        transaction.setMchid("test-mch"); transaction.setAppid("test-app");
        transaction.setTransactionId("WX-1"); transaction.setTradeState(Transaction.TradeStateEnum.SUCCESS);
        TransactionAmount amount = new TransactionAmount(); amount.setTotal(1200); amount.setCurrency("CNY");
        transaction.setAmount(amount);
        when(jsapi.queryOrderByOutTradeNo(any(QueryOrderByOutTradeNoRequest.class))).thenReturn(transaction);
    }

    @Test void successfulQueryRetainsOrderIdentityCurrencyAndMerchantForSharedSettlement() {
        PaymentGateway.PaymentQueryResult result = gateway.queryOrder("MO-1");
        assertThat(result.outTradeNo()).isEqualTo("MO-1");
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.mchId()).isEqualTo("test-mch");
        assertThat(result.appId()).isEqualTo("test-app");
    }

    @Test void mismatchedOrderMerchantAppOrCurrencyNeverBecomesTrustedSuccess() {
        transaction.setOutTradeNo("OTHER"); assertThat(gateway.queryOrder("MO-1")).isNull();
        transaction.setOutTradeNo("MO-1"); transaction.setMchid("other-mch");
        assertThat(gateway.queryOrder("MO-1")).isNull();
        transaction.setMchid("test-mch"); transaction.setAppid("other-app");
        assertThat(gateway.queryOrder("MO-1")).isNull();
        transaction.setAppid("test-app"); transaction.getAmount().setCurrency("USD");
        assertThat(gateway.queryOrder("MO-1")).isNull();
    }

    @Test void remoteCloseSuccessIsReportedOnlyAfterWechatAcknowledgesIt() {
        assertThat(gateway.closeOrder("MO-1")).isTrue();
        ArgumentCaptor<CloseOrderRequest> request = ArgumentCaptor.forClass(CloseOrderRequest.class);
        verify(jsapi).closeOrder(request.capture());
        assertThat(request.getValue().getMchid()).isEqualTo("test-mch");
        assertThat(request.getValue().getOutTradeNo()).isEqualTo("MO-1");
        doThrow(new RuntimeException("network unavailable")).when(jsapi).closeOrder(any());
        assertThat(gateway.closeOrder("MO-1")).isFalse();
    }

    @Test void partialRefundSendsOriginalTotalAndLegacyOverloadStillMeansFullRefund() {
        gateway.refund("MO-1", "RF-1", 300L, 1200L, "部分退货");
        gateway.refund("MO-2", "RF-2", 800L, "整单退货");
        ArgumentCaptor<CreateRequest> requests = ArgumentCaptor.forClass(CreateRequest.class);
        verify(refunds, times(2)).create(requests.capture());
        assertThat(requests.getAllValues().get(0).getAmount().getRefund()).isEqualTo(300L);
        assertThat(requests.getAllValues().get(0).getAmount().getTotal()).isEqualTo(1200L);
        assertThat(requests.getAllValues().get(1).getAmount().getRefund()).isEqualTo(800L);
        assertThat(requests.getAllValues().get(1).getAmount().getTotal()).isEqualTo(800L);
    }

    @Test void uncertainRefundNetworkSdkOrServerFailureKeepsOriginalRefundNumberPending() {
        doThrow(new RuntimeException("request timed out")).when(refunds).create(any());
        PaymentGateway.RefundApplyResult timeout = gateway.refund("MO-1", "RF-1", 300L, 1200L, "test");
        assertThat(timeout.accepted()).isFalse(); assertThat(timeout.unknown()).isTrue();
        doThrow(new com.wechat.pay.java.core.exception.ValidationException("bad signature")).when(refunds).create(any());
        assertThat(gateway.refund("MO-1", "RF-1", 300L, 1200L, "test").unknown()).isTrue();
        doThrow(new com.wechat.pay.java.core.exception.ServiceException(
                null, 500, "{\"code\":\"SYSTEM_ERROR\",\"message\":\"busy\"}")).when(refunds).create(any());
        assertThat(gateway.refund("MO-1", "RF-1", 300L, 1200L, "test").unknown()).isTrue();
        doReturn(null).when(refunds).create(any());
        assertThat(gateway.refund("MO-1", "RF-1", 300L, 1200L, "test").unknown()).isTrue();
        doReturn(new com.wechat.pay.java.service.refund.model.Refund()).when(refunds).create(any());
        assertThat(gateway.refund("MO-1", "RF-1", 300L, 1200L, "test").unknown()).isTrue();
    }

    @Test void explicitWechatParameterRejectionIsADefiniteFailure() {
        when(refunds.create(any())).thenThrow(new com.wechat.pay.java.core.exception.ServiceException(
                null, 400, "{\"code\":\"PARAM_ERROR\",\"message\":\"invalid\"}"));
        PaymentGateway.RefundApplyResult result = gateway.refund("MO-1", "RF-1", 300L, 1200L, "test");
        assertThat(result.accepted()).isFalse(); assertThat(result.unknown()).isFalse();
    }
}
