package com.wuling.trade.service;

import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest;
import com.wechat.pay.java.service.payments.jsapi.model.PrepayWithRequestPaymentResponse;
import com.wechat.pay.java.service.refund.RefundService;
import com.wuling.trade.pay.wxpay.WxPayProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WechatPayGatewayTest {

    @Test
    void miniAppPrepayPassesTimeExpireToWechatRequest() {
        WxPayProperties properties = mock(WxPayProperties.class);
        JsapiServiceExtension jsapiService = mock(JsapiServiceExtension.class);
        RefundService refundService = mock(RefundService.class);
        when(properties.getAppId()).thenReturn("wx-app");
        when(properties.getMchId()).thenReturn("mch-1");
        when(properties.getNotifyUrl()).thenReturn("https://example.test/notify");
        PrepayWithRequestPaymentResponse response = new PrepayWithRequestPaymentResponse();
        response.setAppId("wx-app");
        response.setTimeStamp("1700000000");
        response.setNonceStr("nonce");
        response.setPackageVal("prepay_id=wx-prepay");
        response.setSignType("RSA");
        response.setPaySign("sign");
        when(jsapiService.prepayWithRequestPayment(any(PrepayRequest.class)))
                .thenReturn(response);

        WechatPayGateway gateway = new WechatPayGateway(properties, jsapiService, refundService);
        LocalDateTime expireTime = LocalDateTime.of(2026, 9, 26, 20, 0, 0);

        gateway.prepayForMiniApp(
                "GC202609260001", 8800L, "openid-7", "礼品卡购买", expireTime);

        ArgumentCaptor<PrepayRequest> captor = ArgumentCaptor.forClass(PrepayRequest.class);
        verify(jsapiService).prepayWithRequestPayment(captor.capture());
        String timeExpire = captor.getValue().getTimeExpire();
        assertNotNull(timeExpire);
        assertEquals(expireTime, OffsetDateTime.parse(
                timeExpire, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime());
    }
}
