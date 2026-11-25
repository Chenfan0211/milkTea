package com.wuling.trade.pay.yeepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.yeepay.yop.sdk.utils.DigitalEnvelopeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

/**
 * 易宝异步通知处理。
 *
 * <p><b>解密</b>：易宝用「数字信封」加密通知报文——RSA 加密随机密钥、
 * AES 加密业务数据、SHA256 签名。自行实现极易出错（涉及 AES 附加数据、
 * URL-safe Base64、签名分段），故一律走 SDK 的
 * {@link DigitalEnvelopeUtils#decrypt}。
 *
 * <p><b>转发</b>：解密后的业务事件转发给 server 落库，
 * trade-service 不持有入驻/提现数据（它们属于 server）。
 */
@Service
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepayNotifyService {

    private static final Logger log = LoggerFactory.getLogger(YeepayNotifyService.class);

    private final RestClient serverInternalRestClient;
    /**
     * 支付入账服务：易宝支付回调复用与微信相同的 settlePaid 逻辑。
     *
     * <p>用 ObjectProvider 延迟注入避免与 PaymentService 的循环依赖
     * （PaymentService 依赖 PaymentGatewayResolver → YeepayPaymentGateway）。
     */
    private final org.springframework.beans.factory.ObjectProvider<
            com.wuling.trade.service.PaymentService> paymentServiceProvider;

    public YeepayNotifyService(@Qualifier("serverInternalRestClient") RestClient serverInternalRestClient,
                               org.springframework.beans.factory.ObjectProvider<
                                       com.wuling.trade.service.PaymentService> paymentServiceProvider) {
        this.serverInternalRestClient = serverInternalRestClient;
        this.paymentServiceProvider = paymentServiceProvider;
    }

    /**
     * 处理支付结果通知。
     *
     * <p>复用 {@code PaymentService#settlePaid} —— 与微信回调走同一套入账逻辑
     * （行锁 + 状态机 + 金额校验），只是 channelTag 不同，避免两套入账漂移。
     *
     * <p><b>金额校验是硬要求</b>：不校验金额等于允许伪造回调套现，
     * 由 {@code settlePaid} 内部完成。
     *
     * @param root      解密后的通知 JSON
     * @param plaintext 原始明文（留档排查用）
     */
    public void handlePayNotify(JsonNode root, String plaintext) {
        String orderNo = text(root, "orderId");
        String status = text(root, "status");
        String uniqueOrderNo = text(root, "uniqueOrderNo");
        if (orderNo == null || orderNo.isBlank()) {
            log.warn("易宝支付通知缺少 orderId plaintext={}", plaintext);
            return;
        }
        // 易宝支付成功状态：SUCCESS（其它如 FAIL / PROCESSING 不入账）
        if (!"SUCCESS".equalsIgnoreCase(status)) {
            log.info("易宝支付通知非成功态，跳过入账 orderNo={} status={}", orderNo, status);
            return;
        }
        com.wuling.trade.service.PaymentService paymentService =
                paymentServiceProvider.getIfAvailable();
        if (paymentService == null) {
            throw new IllegalStateException("PaymentService 不可用，无法入账");
        }
        // transactionId 用易宝订单号（对应微信的 transaction_id）
        paymentService.applyGatewayCallback(orderNo, uniqueOrderNo, "YEEPAY");
        log.info("易宝支付入账完成 orderNo={} uniqueOrderNo={}", orderNo, uniqueOrderNo);
    }

    /**
     * 转发清算结果通知给 server。
     *
     * <p>server 收到后置 {@code clearing_status=SUCCESS} 并触发分账
     * （未清算时被门禁拦下的记录此时可以走了）。
     */
    public void forwardClearingNotify(JsonNode root, String plaintext) {
        String orderNo = text(root, "orderId");
        String status = text(root, "status");
        if (orderNo == null || orderNo.isBlank()) {
            log.warn("易宝清算通知缺少 orderId plaintext={}", plaintext);
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("orderNo", orderNo);
        body.put("status", nvl(status));
        body.put("ypSettleAmount", nvl(text(root, "ypSettleAmount")));
        body.put("unSplitAmount", nvl(text(root, "unSplitAmount")));
        try {
            serverInternalRestClient.post()
                    .uri("/internal/yeepay/clearing-notify")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            log.info("已转发清算通知 orderNo={} status={} ypSettleAmount={}",
                    orderNo, status, body.get("ypSettleAmount"));
        } catch (Exception e) {
            // 转发失败时抛出让 controller 回 FAIL，易宝会重试
            throw new IllegalStateException("转发清算通知失败：" + e.getMessage(), e);
        }
    }

    /**
     * 解密数字信封。
     *
     * @param response 易宝下发的 {@code response} 参数
     * @param appKey   {@code customerIdentification}（用于选择私钥）
     * @return 业务明文（JSON 字符串）
     */
    public String decrypt(String response, String appKey) {
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("通知报文为空");
        }
        String plaintext = (appKey == null || appKey.isBlank())
                ? DigitalEnvelopeUtils.decrypt(response, "RSA2048")
                : DigitalEnvelopeUtils.decrypt(response, appKey, "RSA2048");
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalStateException("通知解密结果为空");
        }
        return plaintext;
    }

    /**
     * 转发入网结果通知给 server。
     *
     * <p>字段映射：易宝 {@code applicationStatus} 直接落库，
     * {@code signUrl} 即电子签章地址（经营者签署用）。
     */
    public void forwardMerchantNotify(JsonNode root, String raw) {
        String requestNo = text(root, "requestNo");
        if (requestNo == null) {
            log.warn("入网通知缺少 requestNo");
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("requestNo", requestNo);
        body.put("applicationStatus", nvl(text(root, "applicationStatus")));
        body.put("merchantNo", nvl(text(root, "merchantNo")));
        body.put("signUrl", nvl(text(root, "signUrl")));
        body.put("rejectReason", nvl(first(text(root, "auditOpinion"), text(root, "rejectReason"))));
        // 原文留档供排查；截断避免超长
        body.put("raw", raw != null && raw.length() > 4000 ? raw.substring(0, 4000) : nvl(raw));
        try {
            serverInternalRestClient.post()
                    .uri("/internal/yeepay/onboard-notify")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            log.info("已转发行入网通知 requestNo={} status={}",
                    requestNo, body.get("applicationStatus"));
        } catch (Exception e) {
            // 转发失败时抛出让 controller 回 FAIL，易宝会重试
            throw new IllegalStateException("转发入网通知失败：" + e.getMessage(), e);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String first(String a, String b) {
        return a != null ? a : b;
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
