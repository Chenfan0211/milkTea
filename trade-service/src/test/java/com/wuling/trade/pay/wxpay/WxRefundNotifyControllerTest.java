package com.wuling.trade.pay.wxpay;

import com.wechat.pay.java.core.notification.Notification;
import com.wuling.trade.pay.giftcard.GiftCardRefundService;
import com.wuling.trade.service.RefundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WxRefundNotifyControllerTest {

    private WxPayNotifyService notifyService;
    private RefundService refundService;
    private GiftCardRefundService giftCardRefundService;
    private WxRefundNotifyController controller;

    @BeforeEach
    void setUp() {
        notifyService = mock(WxPayNotifyService.class);
        refundService = mock(RefundService.class);
        giftCardRefundService = mock(GiftCardRefundService.class);
        controller = new WxRefundNotifyController(
                notifyService, refundService, giftCardRefundService);
    }

    @Test
    void grSuccessRoutesToGiftCardConfirm() {
        when(notifyService.verifyAndDecryptRaw(any(), any(), any(), any(), any()))
                .thenReturn(notification(
                        WxRefundNotifyController.EVENT_REFUND_SUCCESS,
                        """
                        {"out_trade_no":"GC202609260001",
                         "out_refund_no":"GR202609260001",
                         "refund_id":"WXR1",
                         "refund_status":"SUCCESS"}
                        """));

        ResponseEntity<Map<String, String>> response = callNotify();

        assertEquals("SUCCESS", response.getBody().get("code"));
        verify(giftCardRefundService).onRefundResult(
                "GC202609260001", "GR202609260001", true, "WXR1", null);
        verifyNoInteractions(refundService);
    }

    @Test
    void grAbnormalRoutesToGiftCardFail() {
        when(notifyService.verifyAndDecryptRaw(any(), any(), any(), any(), any()))
                .thenReturn(notification(
                        WxRefundNotifyController.EVENT_REFUND_ABNORMAL,
                        """
                        {"out_trade_no":"GC202609260001",
                         "out_refund_no":"GR202609260001",
                         "refund_id":"WXR1",
                         "refund_status":"ABNORMAL"}
                        """));

        ResponseEntity<Map<String, String>> response = callNotify();

        assertEquals("SUCCESS", response.getBody().get("code"));
        verify(giftCardRefundService).onRefundResult(
                eq("GC202609260001"), eq("GR202609260001"), eq(false), isNull(),
                contains("ABNORMAL"));
        verifyNoInteractions(refundService);
    }

    @Test
    void ordinaryRefundStillRoutesToRefundService() {
        when(notifyService.verifyAndDecryptRaw(any(), any(), any(), any(), any()))
                .thenReturn(notification(
                        WxRefundNotifyController.EVENT_REFUND_SUCCESS,
                        """
                        {"out_trade_no":"ORDER-1",
                         "out_refund_no":"RF202609260001",
                         "refund_id":"WXR2",
                         "refund_status":"SUCCESS"}
                        """));

        ResponseEntity<Map<String, String>> response = callNotify();

        assertEquals("SUCCESS", response.getBody().get("code"));
        verify(refundService).onRefundResult("RF202609260001", true, null);
        verifyNoInteractions(giftCardRefundService);
    }

    private Notification notification(String eventType, String plaintext) {
        Notification notification = new Notification();
        notification.setEventType(eventType);
        notification.setPlaintext(plaintext);
        return notification;
    }

    private ResponseEntity<Map<String, String>> callNotify() {
        return controller.notify("SERIAL", "123", "NONCE", "SIGN", "{}");
    }
}
