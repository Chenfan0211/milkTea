package com.wuling.trade.pay.payout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.notification.Notification;
import com.wuling.trade.pay.wxpay.WxPayNotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 微信「商家转账到零钱」结果通知接口（第 16 期提现对接）。
 *
 * <p><b>路径</b>：{@code POST /api/v1/app/payout/notify}（对微信开放，无 JWT）
 *
 * <p><b>安全</b>：与支付回调一致，复用 {@link WxPayNotifyService#verifyAndDecryptRaw}
 * 做「验签 + AES 解密 + 时间戳窗口」三重校验；调用方是微信服务器。
 *
 * <p><b>条件装配</b>：仅 {@code app.pay.wxpay.channel=wxpay} 时注册，
 * 避免 mock 阶段对外暴露无验签能力的接口。
 *
 * <p><b>流程</b>：验签解密 → 解析批次结果 → 转发 server 收敛提现状态。
 */
@RestController
@RequestMapping("/api/v1/app/payout")
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class PayoutNotifyController {

    private static final Logger log = LoggerFactory.getLogger(PayoutNotifyController.class);

    private final WxPayNotifyService notifyService;
    private final PayoutResultForwarder forwarder;
    private final ObjectMapper objectMapper;

    public PayoutNotifyController(WxPayNotifyService notifyService,
                                  PayoutResultForwarder forwarder,
                                  ObjectMapper objectMapper) {
        this.notifyService = notifyService;
        this.forwarder = forwarder;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/notify")
    public ResponseEntity<Map<String, String>> notify(
            @RequestHeader(value = "Wechatpay-Serial", required = false) String serial,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody(required = false) String body) {

        Notification notification;
        try {
            notification = notifyService.verifyAndDecryptRaw(serial, timestamp, nonce, signature, body);
        } catch (Exception e) {
            log.warn("微信转账回调校验失败：{}", e.getMessage());
            return fail("回调验签或解密失败");
        }

        try {
            String plaintext = notification.getPlaintext();
            JsonNode root = objectMapper.readTree(plaintext);
            // 遍历转账明细，逐笔按 out_detail_no（= 提现单号）转发结果
            JsonNode details = root.path("transfer_detail_list");
            if (details.isArray()) {
                for (JsonNode detail : details) {
                    String outDetailNo = text(detail, "out_detail_no");
                    String detailStatus = text(detail, "detail_status");
                    if (!StringUtils.hasText(outDetailNo)) {
                        continue;
                    }
                    boolean success = "SUCCESS".equalsIgnoreCase(detailStatus);
                    String failReason = success ? null : text(detail, "fail_reason");
                    forwarder.forward(outDetailNo, success,
                            failReason == null ? "微信转账失败" : failReason);
                }
            } else {
                // 无明细列表（异常报文）：记日志，按成功应答避免微信无意义重试
                log.warn("微信转账回调缺少 transfer_detail_list plaintext={}", plaintext);
            }
            return success();
        } catch (Exception e) {
            log.error("微信转账回调处理失败 err={}", e.getMessage(), e);
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
        return ResponseEntity.ok(Map.of("code", "FAIL", "message", message));
    }
}
