package com.wuling.trade.pay.wxpay;

import com.wechat.pay.java.core.notification.Notification;
import com.wuling.trade.pay.giftcard.GiftCardRefundService;
import com.wuling.trade.service.RefundService;
import com.wechat.pay.java.core.util.GsonUtil;
import com.wechat.pay.java.service.refund.model.RefundNotification;
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
    /**
     * 回归守卫：退款通知明文必须用 Gson 的 snake_case 序列化，而非 Jackson 的 camelCase。
     *
     * <p>根因：RefundNotification 用 Gson 的 @SerializedName 标注（out_trade_no 等），
     * 若用 Jackson ObjectMapper 序列化会输出 outTradeNo/outRefundNo，
     * 下游 WxRefundNotifyController 按 out_trade_no/out_refund_no 读不到 → 回调被误判失败。
     */
    @Test
    void refundNotificationSerializesToSnakeCase() {
        RefundNotification n = new RefundNotification();
        n.setOutTradeNo("ORDER-1");
        n.setOutRefundNo("RF1");

        String json = GsonUtil.toJson(n);

        org.junit.jupiter.api.Assertions.assertTrue(
                json.contains("\"out_trade_no\":\"ORDER-1\""),
                "Gson 序列化必须输出 snake_case 的 out_trade_no，实际：" + json);
        org.junit.jupiter.api.Assertions.assertTrue(
                json.contains("\"out_refund_no\":\"RF1\""),
                "Gson 序列化必须输出 snake_case 的 out_refund_no，实际：" + json);
        org.junit.jupiter.api.Assertions.assertFalse(
                json.contains("outTradeNo") || json.contains("outRefundNo"),
                "不得出现 Jackson 风格的 camelCase 字段名，实际：" + json);
    }
}
