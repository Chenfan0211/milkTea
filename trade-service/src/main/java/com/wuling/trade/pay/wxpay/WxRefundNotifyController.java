package com.wuling.trade.pay.wxpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.notification.Notification;
import com.wuling.trade.pay.giftcard.GiftCardRefundService;
import com.wuling.trade.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信退款结果通知接口（第 16 期退款接入）。
 *
 * <p><b>路径</b>：{@code POST /api/v1/app/payments/wxpay/refund-notify}
 *
 * <p><b>语义</b>：退款是异步的，微信在退款状态变化时回调本接口。
 * {@code refund_status} 取值：{@code SUCCESS / CLOSED / PROCESSING / ABNORMAL}。
 * 只有 {@code SUCCESS} 才把退款单置「退款成功」并完成订单退款与台账冲正；
 * {@code CLOSED / ABNORMAL} 置「退款失败」；{@code PROCESSING} 忽略（等终态）。
 *
 * <p><b>安全</b>：复用 {@link WxPayNotifyService#verifyAndDecryptRaw} 的
 * 「时间戳窗口 + RSA 验签 + AES-GCM 解密」，不额外引入验签实现。
 * 本接口无需 JWT（调用方是微信服务器）。
 *
 * <p><b>幂等</b>：{@link RefundService#onRefundResult} 对终态退款单直接跳过，
 * 重复推送不会重复冲正。
 */
@RestController
@RequestMapping("/api/v1/app/payments/wxpay")
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class WxRefundNotifyController {

    private static final Logger log = LoggerFactory.getLogger(WxRefundNotifyController.class);

    /** 微信退款结果通知事件类型 */
    public static final String EVENT_REFUND_SUCCESS = "REFUND.SUCCESS";
    public static final String EVENT_REFUND_ABNORMAL = "REFUND.ABNORMAL";
    public static final String EVENT_REFUND_CLOSED = "REFUND.CLOSED";

    private final WxPayNotifyService notifyService;
    private final RefundService refundService;
    private final GiftCardRefundService giftCardRefundService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WxRefundNotifyController(WxPayNotifyService notifyService,
                                    RefundService refundService,
                                    GiftCardRefundService giftCardRefundService) {
        this.notifyService = notifyService;
        this.refundService = refundService;
        this.giftCardRefundService = giftCardRefundService;
    }

    @PostMapping("/refund-notify")
    public ResponseEntity<Map<String, String>> notify(
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody(required = false) String body) {

        Notification notification;
        try {
            notification = notifyService.verifyAndDecryptRaw(serial, timestamp, nonce, signature, body);
        } catch (WxPayNotifyService.WxPayNotifyException e) {
            log.warn("微信退款回调校验失败：{}", e.getMessage());
            return fail(e.getMessage());
        }

        try {
            String eventType = notification.getEventType();
            // 只处理退款终态事件；PROCESSING 等非终态按 SUCCESS 应答，避免无意义重试
            if (!EVENT_REFUND_SUCCESS.equals(eventType)
                    && !EVENT_REFUND_ABNORMAL.equals(eventType)
                    && !EVENT_REFUND_CLOSED.equals(eventType)) {
                log.info("微信退款回调事件非终态，已忽略 eventType={}", eventType);
                return success();
            }

            JsonNode node = objectMapper.readTree(notification.getPlaintext());
            String orderNo = text(node, "out_trade_no");
            String refundNo = text(node, "out_refund_no");
            String refundStatus = text(node, "refund_status");
            if (!StringUtils.hasText(orderNo) || !StringUtils.hasText(refundNo)) {
                log.warn("微信退款回调缺少 out_trade_no 或 out_refund_no");
                return fail("回调缺少商户订单号或退款单号");
            }

            boolean success = "SUCCESS".equals(refundStatus);
            // CLOSED / ABNORMAL 均为失败；PROCESSING 理论上已被上面过滤
            if (refundNo.startsWith("GR")) {
                giftCardRefundService.onRefundResult(
                        orderNo,
                        refundNo,
                        success,
                        success ? text(node, "refund_id") : null,
                        success ? null : "微信退款状态：" + refundStatus);
            } else {
                refundService.onRefundResult(refundNo, success,
                        success ? null : "微信退款状态：" + refundStatus);
            }
            return success();
        } catch (Exception e) {
            log.error("微信退款回调处理失败 err={}", e.getMessage(), e);
            return fail("回调处理失败");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private ResponseEntity<Map<String, String>> success() {
        return ResponseEntity.ok(Map.of("code", "SUCCESS"));
    }

    private ResponseEntity<Map<String, String>> fail(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "FAIL", "message", message == null ? "处理失败" : message));
    }
}
