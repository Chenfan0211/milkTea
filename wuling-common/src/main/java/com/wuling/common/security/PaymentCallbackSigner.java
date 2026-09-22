package com.wuling.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 支付回调签名校验器。
 *
 * <p>背景（P0 安全修复）：原 {@code POST /api/v1/app/payments/callback} 无任何鉴权，
 * 任何人构造 {@code orderNo} + {@code transactionId} 即可把订单置为已支付并拿到取餐码
 * （实测可将 CREATED/UNPAID 订单改为 PAID 并获得有效取餐码，等于免费取餐）。
 *
 * <p>方案：仿第三方支付通道的回调约定，回调方需用共享密钥对「订单号 + 交易号 + 时间戳」
 * 做 HMAC-SHA256 签名，服务端用<b>常量时间比较</b>校验，并校验时间戳窗口防重放。
 *
 * <p>签名串格式（字段间用 {@code \n} 分隔，避免字段拼接歧义）：
 * <pre>{@code
 *   orderNo + "\n" + transactionId + "\n" + timestamp
 * }</pre>
 *
 * <p>安全要点：
 * <ul>
 *   <li>使用 {@link MessageDigest#isEqual} 做常量时间比较，防时序侧信道；</li>
 *   <li>时间戳窗口默认 5 分钟，限制重放窗口；</li>
 *   <li>密钥长度不足直接拒绝，避免弱密钥；</li>
 *   <li>本类不记录签名内容与密钥，避免敏感信息进日志。</li>
 * </ul>
 */
public final class PaymentCallbackSigner {

    /** 默认允许的时间漂移窗口：5 分钟 */
    public static final long DEFAULT_TOLERANCE_MS = 5 * 60 * 1000L;

    /** HMAC 密钥最小长度（字节），低于此值视为弱密钥 */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secret;
    private final long toleranceMs;

    /**
     * @param secret      共享密钥（建议 32 字节以上随机值，经部署环境注入）
     * @param toleranceMs 允许的时间漂移（毫秒）；传 0 表示不校验时间戳
     */
    public PaymentCallbackSigner(String secret, long toleranceMs) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "支付回调密钥长度不足（至少 " + MIN_SECRET_BYTES + " 字节），请在部署环境注入 PAY_CALLBACK_SECRET");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceMs = toleranceMs;
    }

    /** 用默认 5 分钟窗口构造 */
    public static PaymentCallbackSigner of(String secret) {
        return new PaymentCallbackSigner(secret, DEFAULT_TOLERANCE_MS);
    }

    /**
     * 生成签名（供回调方 / 测试使用）。
     *
     * @return 小写十六进制 HMAC-SHA256
     */
    public String sign(String orderNo, String transactionId, long timestamp) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(payload(orderNo, transactionId, timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("计算支付回调签名失败", e);
        }
    }

    /**
     * 校验签名与时间戳。
     *
     * @param now 当前时间（毫秒），由调用方传入便于测试
     * @return true=校验通过
     */
    public boolean verify(String orderNo, String transactionId, long timestamp, String signature, long now) {
        if (orderNo == null || transactionId == null || signature == null) {
            return false;
        }
        if (toleranceMs > 0 && Math.abs(now - timestamp) > toleranceMs) {
            return false;
        }
        byte[] expected = sign(orderNo, transactionId, timestamp).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        // 常量时间比较，避免时序侧信道
        return MessageDigest.isEqual(expected, actual);
    }

    private String payload(String orderNo, String transactionId, long timestamp) {
        return orderNo + "\n" + transactionId + "\n" + timestamp;
    }
}
