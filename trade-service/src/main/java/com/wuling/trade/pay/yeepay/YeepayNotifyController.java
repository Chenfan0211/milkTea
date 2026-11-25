package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.trade.pay.payout.PayoutResultForwarder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 易宝异步通知接收。
 *
 * <p><b>路径</b>：{@code POST /api/v1/app/yeepay/notify/{merchant|settle}}
 *
 * <p><b>报文格式</b>：{@code application/x-www-form-urlencoded}，
 * 含 {@code response}（数字信封密文）与 {@code customerIdentification}（appKey）。
 *
 * <p><b>应答要求</b>：必须回写纯文本大写 {@code SUCCESS}，否则易宝按
 * 5/10/15/30/240/600/2700/3600/14400 秒重试 9 次。
 * 这与微信的 JSON 应答不同，是本项目最容易踩的坑之一。
 *
 * <p><b>解密</b>：{@code response} 用 {@code $} 分隔为四段
 * （RSA 加密的随机密钥 / AES 加密的业务数据+签名 / AES / SHA256）。
 * 优先用 SDK 的 {@code DigitalEnvelopeUtils} 解密，避免自行实现 AES-GCM
 * 与签名验证的细节出错。
 */
@RestController
@RequestMapping("/api/v1/app/yeepay/notify")
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayNotifyController {

    private static final Logger log = LoggerFactory.getLogger(YeepayNotifyController.class);

    private final YeepayNotifyService notifyService;
    private final PayoutResultForwarder forwarder;
    private final ObjectMapper objectMapper;

    public YeepayNotifyController(YeepayNotifyService notifyService,
                                   PayoutResultForwarder forwarder,
                                   ObjectMapper objectMapper) {
        this.notifyService = notifyService;
        this.forwarder = forwarder;
        this.objectMapper = objectMapper;
    }

    /**
     * 支付结果通知。
     *
     * <p>调用方是易宝服务器，验签解密后交给 {@code PaymentService} 入账
     * （复用与微信相同的 {@code settlePaid}，只是 channelTag 不同）。
     *
     * <p><b>必须回写纯文本 SUCCESS</b>，否则易宝重试 9 次。
     */
    @PostMapping(value = "/pay", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> payNotify(
            @RequestParam(value = "response", required = false) String response,
            @RequestParam(value = "customerIdentification", required = false) String appKey) {
        String plaintext;
        try {
            plaintext = notifyService.decrypt(response, appKey);
        } catch (Exception e) {
            log.warn("易宝支付通知解密失败：{}", e.getMessage());
            return ResponseEntity.ok("FAIL");
        }
        try {
            JsonNode root = objectMapper.readTree(plaintext);
            notifyService.handlePayNotify(root, plaintext);
            return ResponseEntity.ok("SUCCESS");
        } catch (Exception e) {
            // 入账失败必须回 FAIL 让易宝重试，否则用户已付款而订单仍待支付
            log.error("易宝支付通知处理失败 err={}", e.getMessage(), e);
            return ResponseEntity.ok("FAIL");
        }
    }

    /**
     * 清算结果通知。
     *
     * <p>易宝要求「清算成功后才能分账/退款」。核销早于清算时，
     * 分账会被门禁标记为 {@code PENDING_CLEARING}；
     * 本通知到达后触发补分账。
     */
    @PostMapping(value = "/clearing", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> clearingNotify(
            @RequestParam(value = "response", required = false) String response,
            @RequestParam(value = "customerIdentification", required = false) String appKey) {
        String plaintext;
        try {
            plaintext = notifyService.decrypt(response, appKey);
        } catch (Exception e) {
            log.warn("易宝清算通知解密失败：{}", e.getMessage());
            return ResponseEntity.ok("FAIL");
        }
        try {
            JsonNode root = objectMapper.readTree(plaintext);
            notifyService.forwardClearingNotify(root, plaintext);
            return ResponseEntity.ok("SUCCESS");
        } catch (Exception e) {
            log.error("易宝清算通知处理失败 err={}", e.getMessage(), e);
            return ResponseEntity.ok("FAIL");
        }
    }

    /**
     * 入网结果通知：审核状态变化 + 电子签章地址。
     */
    @PostMapping(value = "/merchant", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> merchantNotify(
            @RequestParam(value = "response", required = false) String response,
            @RequestParam(value = "customerIdentification", required = false) String appKey) {
        String plaintext;
        try {
            plaintext = notifyService.decrypt(response, appKey);
        } catch (Exception e) {
            log.warn("易宝入网通知解密失败：{}", e.getMessage());
            // 回 FAIL 让易宝重试；返回 200 避免触发 HTTP 层错误
            return ResponseEntity.ok("FAIL");
        }
        try {
            JsonNode root = objectMapper.readTree(plaintext);
            notifyService.forwardMerchantNotify(root, plaintext);
            return ResponseEntity.ok("SUCCESS");
        } catch (Exception e) {
            log.error("易宝入网通知处理失败 err={}", e.getMessage(), e);
            return ResponseEntity.ok("FAIL");
        }
    }

    /**
     * 结算结果通知：提现出款成败。
     */
    @PostMapping(value = "/settle", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> settleNotify(
            @RequestParam(value = "response", required = false) String response,
            @RequestParam(value = "customerIdentification", required = false) String appKey) {
        String plaintext;
        try {
            plaintext = notifyService.decrypt(response, appKey);
        } catch (Exception e) {
            log.warn("易宝结算通知解密失败：{}", e.getMessage());
            return ResponseEntity.ok("FAIL");
        }
        try {
            JsonNode root = objectMapper.readTree(plaintext);
            String merchantNo = text(root, "merchantNo");
            String settleRequestNo = text(root, "settleRequestNo");
            String status = text(root, "settleStatus");
            if (settleRequestNo == null) {
                log.warn("易宝结算通知缺少 settleRequestNo plaintext={}", plaintext);
                return ResponseEntity.ok("SUCCESS");
            }
            // settleRequestNo 即提现单号（server 侧的约定）
            boolean success = "SUCCESS".equalsIgnoreCase(status);
            String failReason = success ? null : text(root, "failReason");
            forwarder.forward(settleRequestNo, success,
                    failReason == null ? "易宝结算失败" : failReason);
            log.info("易宝结算通知 merchantNo={} requestNo={} status={}",
                    merchantNo, settleRequestNo, status);
            return ResponseEntity.ok("SUCCESS");
        } catch (Exception e) {
            log.error("易宝结算通知处理失败 err={}", e.getMessage(), e);
            return ResponseEntity.ok("FAIL");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
