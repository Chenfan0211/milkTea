package com.wuling.trade.pay.wxpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.pay.java.core.notification.Notification;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.refund.model.RefundNotification;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RequestParam;
import com.wechat.pay.java.core.util.GsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 微信支付回调「验签 + 解密」服务（第 14 期支付接入）。
 *
 * <p><b>职责边界</b>：本类只负责把「微信发来的一坨密文」变成可信的
 * {@link WxPayTransaction}，<b>不做任何入账动作</b>。
 * 入账由 {@code PaymentService} 完成（它已具备订单行锁 + 状态机幂等）。
 * 这样拆分的理由：验签是纯粹的安全边界，入账是资金逻辑，
 * 两者失败语义完全不同（验签失败要回 FAIL 让微信重试；
 * 入账失败要区分「已处理过」与「真出错」）。
 *
 * <p><b>为什么不能复用 {@code PaymentCallbackSigner}</b>：
 * 那是 HMAC 共享密钥，面向「自家网关回调」；
 * 微信 APIv3 用「微信支付公钥/平台证书 + RSA-SHA256 验签 + AES-256-GCM 解密」，
 * 协议完全不同，混用必然验签失败。
 *
 * <p><b>防重放</b>：校验 {@code Wechatpay-Timestamp} 与当前时间偏差，
 * 默认窗口 5 分钟（{@code app.pay.wxpay.notify-tolerance-seconds}）。
 * 注意 SDK 的验签本身<b>不含</b>时间戳窗口校验，必须在此显式补上。
 *
 * <p><b>条件装配</b>：本类依赖 SDK 的 {@code NotificationParser}，
 * 而后者只在 {@code app.pay.channel=wxpay} 时创建。
 * 因此本类同样声明条件装配 —— 否则 mock 通道下会因缺少
 * {@code NotificationParser} 导致启动失败。
 */
@Service
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class WxPayNotifyService {

    private static final Logger log = LoggerFactory.getLogger(WxPayNotifyService.class);

    /** 微信支付成功事件类型 */
    public static final String EVENT_TRANSACTION_SUCCESS = "TRANSACTION.SUCCESS";

    private final WxPayProperties properties;
    private final NotificationParser notificationParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WxPayNotifyService(WxPayProperties properties, NotificationParser notificationParser) {
        this.properties = properties;
        this.notificationParser = notificationParser;
    }

    /**
     * 校验并解析回调。
     *
     * <p>处理顺序严格照微信规范：
     * <ol>
     *   <li>校验必填请求头；</li>
     *   <li>校验时间戳窗口（防重放）；</li>
     *   <li>RSA 验签 + AES-GCM 解密（SDK 完成）；</li>
     *   <li>转为 {@link WxPayTransaction} 并回填事件类型 / 通知 ID。</li>
     * </ol>
     *
     * @param serial     {@code Wechatpay-Serial} 请求头
     * @param timestamp  {@code Wechatpay-Timestamp} 请求头
     * @param nonce      {@code Wechatpay-Nonce} 请求头
     * @param signature  {@code Wechatpay-Signature} 请求头
     * @param body       原始请求体（<b>必须为未改动的原始字符串</b>，否则验签必失败）
     * @return 解密后的交易信息
     * @throws WxPayNotifyException 任一环节失败；调用方应答 FAIL 让微信重试
     */
    public WxPayTransaction verifyAndDecrypt(String serial, String timestamp, String nonce,
                                             String signature, String body) {
        requireText(serial, "Wechatpay-Serial");
        requireText(timestamp, "Wechatpay-Timestamp");
        requireText(nonce, "Wechatpay-Nonce");
        requireText(signature, "Wechatpay-Signature");
        requireText(body, "请求体");

        checkTimestampWindow(timestamp);

        JsonNode outer;
        Transaction decrypted;
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .timestamp(timestamp)
                    .nonce(nonce)
                    .signature(signature)
                    .body(body)
                    .build();
            // 外层（event_type / id 等不加密字段）从原始 body 解析
            outer = objectMapper.readTree(body);
            // 解密明文：正确传入 Transaction.class，SDK 把解密后的交易反序列化为此类
            decrypted = notificationParser.parse(requestParam, Transaction.class);
        } catch (Exception e) {
            // 不打印签名与密文内容，避免敏感信息进日志
            log.warn("微信支付回调验签/解密失败 serial={} err={}", serial, e.getMessage());
            throw new WxPayNotifyException("回调验签或解密失败", e);
        }

        WxPayTransaction transaction = fromTransaction(decrypted);
        transaction.setEventType(text(outer, "event_type"));
        transaction.setNotifyId(text(outer, "id"));

        // 校验报文里的商户号/AppID 与自身配置一致，防止「别人家的回调」被误认入账
        if (StringUtils.hasText(transaction.getMchId())
                && !transaction.getMchId().equals(properties.getMchId())) {
            log.error("微信支付回调商户号不匹配 expected={} actual={}",
                    properties.getMchId(), transaction.getMchId());
            throw new WxPayNotifyException("回调商户号不匹配");
        }
        if (StringUtils.hasText(transaction.getAppId())
                && !transaction.getAppId().equals(properties.getAppId())) {
            log.error("微信支付回调 AppID 不匹配 expected={} actual={}",
                    properties.getAppId(), transaction.getAppId());
            throw new WxPayNotifyException("回调 AppID 不匹配");
        }
        if (!StringUtils.hasText(transaction.getOutTradeNo())) {
            throw new WxPayNotifyException("回调缺少商户订单号 out_trade_no");
        }
        return transaction;
    }

    /**
     * 校验并解密回调，返回原始 {@link Notification}（含明文 plaintext）。
     *
     * <p>供退款结果通知等非「支付成功」事件复用：支付入账走
     * {@link #verifyAndDecrypt(String, String, String, String, String)}，
     * 退款结果通知需要按 refund_status 自行解析，故暴露此通用方法。
     */
    public Notification verifyAndDecryptRaw(String serial, String timestamp, String nonce,
                                            String signature, String body) {
        requireText(serial, "Wechatpay-Serial");
        requireText(timestamp, "Wechatpay-Timestamp");
        requireText(nonce, "Wechatpay-Nonce");
        requireText(signature, "Wechatpay-Signature");
        requireText(body, "请求体");
        checkTimestampWindow(timestamp);
        try {
            RequestParam requestParam = new RequestParam.Builder()
                    .serialNumber(serial)
                    .timestamp(timestamp)
                    .nonce(nonce)
                    .signature(signature)
                    .body(body)
                    .build();
            // 外层（event_type / id 等）从原始 body 解析
            JsonNode outer = objectMapper.readTree(body);
            Notification notification = new Notification();
            notification.setEventType(text(outer, "event_type"));
            notification.setId(text(outer, "id"));
            // 解密明文：退款通知明文对应 RefundNotification
            RefundNotification decrypted = notificationParser.parse(requestParam, RefundNotification.class);
            // 把解密明文回填到 plaintext，供调用方按 refund_status 解析。
            // 必须用 GsonUtil（Gson + LOWER_CASE_WITH_UNDERSCORES）序列化：
            // RefundNotification 字段用 Gson 的 @SerializedName 标注（如 out_trade_no），
            // Jackson 的 ObjectMapper 不识别该注解，会把字段名序列化成 camelCase，
            // 导致下游 WxRefundNotifyController 按 out_trade_no/out_refund_no 读不到 → 回调被误判失败。
            notification.setPlaintext(GsonUtil.toJson(decrypted));
            return notification;
        } catch (Exception e) {
            log.warn("微信支付回调验签/解密失败 serial={} err={}", serial, e.getMessage());
            throw new WxPayNotifyException("回调验签或解密失败", e);
        }
    }
    /** 时间戳窗口校验，防重放 */
    private void checkTimestampWindow(String timestamp) {
        long tolerance = properties.getNotifyToleranceSeconds();
        if (tolerance <= 0) {
            return;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new WxPayNotifyException("回调时间戳格式非法");
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        if (Math.abs(nowSeconds - ts) > tolerance) {
            log.warn("微信支付回调时间戳超出允许窗口 diff={}s tolerance={}s", nowSeconds - ts, tolerance);
            throw new WxPayNotifyException("回调时间戳超出允许窗口，疑似重放");
        }
    }

    /** 把 SDK 解密出的 {@link Transaction} 映射为 {@link WxPayTransaction} */
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private WxPayTransaction fromTransaction(Transaction tx) {
        if (tx == null) {
            throw new WxPayNotifyException("回调解密结果为空");
        }
        WxPayTransaction result = new WxPayTransaction();
        result.setOutTradeNo(tx.getOutTradeNo());
        result.setTransactionId(tx.getTransactionId());
        result.setTradeState(tx.getTradeState() == null ? null : tx.getTradeState().name());
        result.setTradeStateDesc(tx.getTradeStateDesc());
        result.setMchId(tx.getMchid());
        result.setAppId(tx.getAppid());
        result.setSuccessTime(tx.getSuccessTime());
        if (tx.getAmount() != null) {
            if (tx.getAmount().getTotal() != null) {
                result.setTotalAmount(tx.getAmount().getTotal().longValue());
            }
            if (tx.getAmount().getPayerTotal() != null) {
                result.setPayerTotal(tx.getAmount().getPayerTotal().longValue());
            }
        }
        if (tx.getPayer() != null) {
            result.setPayerOpenid(tx.getPayer().getOpenid());
        }
        return result;
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new WxPayNotifyException("回调缺少必要参数：" + field);
        }
    }

    /**
     * 回调处理异常。
     *
     * <p>刻意做成运行时异常且<b>携带明确语义</b>：
     * 调用方需据此应答 {@code {"code":"FAIL"}}，微信会按策略重试。
     * 不要把它当成「业务异常」返回给前端 —— 该接口只对微信开放。
     */
    public static class WxPayNotifyException extends RuntimeException {
        public WxPayNotifyException(String message) {
            super(message);
        }

        public WxPayNotifyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
