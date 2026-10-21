package com.wuling.user.tongue;

import com.wuling.user.config.TongueProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 舌象第三方协议签名器。
 *
 * <p>固定协议：
 * <pre>
 * POST contentMd5 = MD5(完整 JSON body bytes)
 * GET  contentMd5 = MD5("reportId=" + URL 编码后的 reportId)
 * sign = SHA-256("appKey=...&amp;timestamp=...&amp;nonce=...&amp;contentMd5=...&amp;appSecret=...")
 * </pre>
 *
 * <p>MD5、SHA-256 输出均为大写十六进制。nonce 使用去掉连字符的 UUID。
 */
@Component
public class TongueSigner {

    private final TongueProperties properties;

    public TongueSigner(TongueProperties properties) {
        this.properties = properties;
    }

    public SignedHeaders signPost(byte[] body) {
        return sign(String.valueOf(System.currentTimeMillis()), newNonce(), md5Hex(body));
    }

    public SignedHeaders signGet(String reportId) {
        String encodedReportId = urlEncode(reportId);
        String contentMd5 = md5Hex(("reportId=" + encodedReportId).getBytes(StandardCharsets.UTF_8));
        return sign(String.valueOf(System.currentTimeMillis()), newNonce(), contentMd5);
    }

    public SignedHeaders sign(String timestamp, String nonce, String contentMd5) {
        requireConfigured();
        String source = "appKey=" + properties.getAppKey()
                + "&timestamp=" + timestamp
                + "&nonce=" + nonce
                + "&contentMd5=" + contentMd5
                + "&appSecret=" + properties.getAppSecret();
        return new SignedHeaders(
                properties.getAppKey(),
                properties.getAppSecret(),
                timestamp,
                nonce,
                contentMd5,
                sha256Hex(source)
        );
    }

    public String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void requireConfigured() {
        if (!StringUtils.hasText(properties.getAppKey())
                || !StringUtils.hasText(properties.getAppSecret())) {
            throw new TongueClientException("舌象检测服务未配置");
        }
    }

    private static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String md5Hex(byte[] bytes) {
        return digestHex("MD5", bytes);
    }

    private static String sha256Hex(String value) {
        return digestHex("SHA-256", value.getBytes(StandardCharsets.UTF_8));
    }

    private static String digestHex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().withUpperCase()
                    .formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("签名算法不可用", e);
        }
    }

    public record SignedHeaders(
            String appKey,
            String appSecret,
            String timestamp,
            String nonce,
            String contentMd5,
            String sign
    ) {
    }
}
