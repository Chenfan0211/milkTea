package com.wuling.trade.pay.yeepay;

import com.yeepay.yop.sdk.config.enums.CertStoreType;
import com.yeepay.yop.sdk.config.provider.file.YopCertConfig;
import com.yeepay.yop.sdk.gm.security.cert.parser.YopSm2PrivateKeyParser;
import com.yeepay.yop.sdk.inter.utils.RSAKeyUtils;
import com.yeepay.yop.sdk.security.CertTypeEnum;

import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 易宝商户私钥的「类型识别 + 解析」工具。
 *
 * <p><b>为什么需要自动识别</b>：易宝会根据应用签约的类型签发不同算法的私钥——
 * 国际算法是 {@code RSA2048}，国密是 {@code SM2}。运营从开放平台下载后直接粘贴，
 * 不该再让人去选「这是 RSA 还是 SM2」：选错不会有明显报错，只会得到
 * 「签名验签失败」这种极难排查的故障。
 *
 * <p><b>识别方式</b>：解析 PKCS#8 的 DER 头，读 AlgorithmIdentifier 里的 OID。
 * <ul>
 *   <li>{@code 1.2.840.10045.2.1} + 曲线 {@code 1.2.156.10197.1.301} → SM2（国密）</li>
 *   <li>{@code 1.2.840.113549.1.1.1} → RSA</li>
 *   <li>OID 为 {@code ecPublicKey} 但曲线是 {@code prime256v1}/{@code secp256r1} → 非易宝预期，按 SM2 之外处理</li>
 * </ul>
 *
 * <p><b>解析分工</b>：SM2 交给 SDK 的 {@link YopSm2PrivateKeyParser}
 * （由 {@code yop-java-sdk-crypto-gm} 提供，内部依赖 BouncyCastle）；
 * RSA 交给 {@link RSAKeyUtils}（{@code yop-java-sdk-crypto-inter}）。
 */
final class YeepayPrivateKeySupport {

    /** SM2 国密曲线的 OID：1.2.156.10197.1.301 */
    private static final String OID_SM2_CURVE = "06082A811CCF5501822D";
    /** RSA 算法的 OID：1.2.840.113549.1.1.1 */
    private static final String OID_RSA = "06092A864886F70D010101";

    private static final Pattern PEM_HEADER = Pattern.compile("-----[A-Z ]+-----");

    private YeepayPrivateKeySupport() {
    }

    /**
     * 按私钥内容识别证书类型。
     *
     * <p>识别不确定时<b>回落到 RSA2048</b>：这是历史默认值，
     * 保持对既有配置的兼容；真正的失败会在 {@link #parse} 阶段暴露。
     */
    static CertTypeEnum detectCertType(String privateKeyText) {
        String hex = toDerHex(privateKeyText);
        if (hex == null) {
            return CertTypeEnum.RSA2048;
        }
        if (hex.contains(OID_SM2_CURVE)) {
            return CertTypeEnum.SM2;
        }
        if (hex.contains(OID_RSA)) {
            return CertTypeEnum.RSA2048;
        }
        return CertTypeEnum.RSA2048;
    }

    /**
     * 按指定类型解析私钥。
     *
     * @param privateKeyText 私钥文本（可带 PEM 头尾与换行，内部会清洗）
     * @param certType       证书类型
     * @return 私钥；解析失败抛异常，由调用方给出可操作提示
     */
    static PrivateKey parse(String privateKeyText, CertTypeEnum certType) {
        String cleaned = clean(privateKeyText);
        if (certType == CertTypeEnum.SM2) {
            YopCertConfig certConfig = new YopCertConfig();
            certConfig.setCertType(CertTypeEnum.SM2);
            certConfig.setStoreType(CertStoreType.STRING);
            certConfig.setValue(cleaned);
            return new YopSm2PrivateKeyParser().parse(certConfig);
        }
        return RSAKeyUtils.string2PrivateKey(cleaned);
    }

    /** 是否 RSA 私钥（用于日志与错误提示措辞）。 */
    static boolean isRsa(PrivateKey key) {
        return key instanceof RSAPrivateKey;
    }

    /** 去掉 PEM 头尾与所有空白，SDK 期望纯 Base64。 */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        return PEM_HEADER.matcher(raw).replaceAll("").replaceAll("\\s+", "");
    }

    /**
     * 把 Base64 私钥解成 DER 并转成大写十六进制，供 OID 匹配。
     * 非法 Base64 返回 null（走回落逻辑）。
     */
    private static String toDerHex(String privateKeyText) {
        String cleaned = clean(privateKeyText);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        try {
            byte[] der = java.util.Base64.getDecoder().decode(cleaned);
            StringBuilder sb = new StringBuilder(der.length * 2);
            for (byte b : der) {
                sb.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
