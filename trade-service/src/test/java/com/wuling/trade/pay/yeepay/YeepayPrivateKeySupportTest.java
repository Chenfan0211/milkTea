package com.wuling.trade.pay.yeepay;

import com.yeepay.yop.sdk.security.CertTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 易宝私钥「类型识别 + 解析」的回归测试。
 *
 * <p><b>背景</b>：2026-11-27 拿到易宝应用密钥后发现它是 SM2（国密）私钥，
 * 而原实现只支持 RSA2048——{@code RSAKeyUtils.string2PrivateKey} 会抛
 * {@code IsvPrivateKey ParseFail}，直接导致 trade-service 起不来。
 *
 * <p>修复引入 {@code yop-java-sdk-crypto-gm} 并按 OID 自动识别类型。
 * 本测试锁住三点：
 * <ol>
 *   <li>SM2 私钥能被识别并解析（否则国密应用无法对接）；</li>
 *   <li>RSA 私钥仍按 RSA2048 识别（不能因为加了国密就漏掉老路径）；</li>
 *   <li>PEM 头尾与换行能被清洗（运营直接粘贴带格式的私钥是常态）。</li>
 * </ol>
 *
 * <p>测试私钥为易宝开放平台下发的测试应用密钥，不涉及生产资金账户。
 */
class YeepayPrivateKeySupportTest {

    /** SM2（国密）私钥：PKCS#8，曲线 OID 1.2.156.10197.1.301 */
    private static final String SM2_PRIVATE_KEY =
            "MIGTAgEAMBMGByqGSM49AgEGCCqBHM9VAYItBHkwdwIBAQQgGVRDhEATzRLrICn/B2tUEgKOYz6PDk2fDlJnRaiuD/2"
                    + "gCgYIKoEcz1UBgi2hRANCAASmrnhdw74jYdX4niyb/1638pNiJDoOc0k5v9pyfzr+DbqJ5d3SqEzR6ynEgfK/crz"
                    + "8bqkoGe7ccSFrceT1nREe";

    /** RSA2048 私钥（PKCS#8，仅测试用，随机生成）。 */
    private static final String RSA_PRIVATE_KEY = generateRsaKeyForTest();

    private static String generateRsaKeyForTest() {
        try {
            java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            java.security.KeyPair kp = gen.generateKeyPair();
            return java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("生成测试 RSA 私钥失败", e);
        }
    }

    @Test
    @DisplayName("SM2 私钥能被识别为 SM2，并成功解析")
    void detectsAndParsesSm2Key() {
        assertEquals(CertTypeEnum.SM2, YeepayPrivateKeySupport.detectCertType(SM2_PRIVATE_KEY),
                "SM2 曲线 OID 必须被识别为 SM2");

        PrivateKey key = YeepayPrivateKeySupport.parse(SM2_PRIVATE_KEY, CertTypeEnum.SM2);
        assertNotNull(key, "SM2 私钥解析结果不能为空");
        assertFalse(YeepayPrivateKeySupport.isRsa(key), "SM2 私钥不应是 RSAPrivateKey");
    }

    @Test
    @DisplayName("RSA 私钥仍识别为 RSA2048，老路径不受影响")
    void detectsAndParsesRsaKey() {
        assertEquals(CertTypeEnum.RSA2048, YeepayPrivateKeySupport.detectCertType(RSA_PRIVATE_KEY),
                "RSA 私钥必须被识别为 RSA2048");

        PrivateKey key = YeepayPrivateKeySupport.parse(RSA_PRIVATE_KEY, CertTypeEnum.RSA2048);
        assertNotNull(key, "RSA 私钥解析结果不能为空");
        assertTrue(YeepayPrivateKeySupport.isRsa(key), "RSA 私钥应是 RSAPrivateKey");
    }

    @Test
    @DisplayName("带 PEM 头尾与换行的私钥能被清洗后解析")
    void parsesPemWrappedKey() {
        String wrapped = "-----BEGIN PRIVATE KEY-----\n"
                + SM2_PRIVATE_KEY.replaceAll("(.{64})", "$1\n")
                + "\n-----END PRIVATE KEY-----";

        assertEquals(CertTypeEnum.SM2, YeepayPrivateKeySupport.detectCertType(wrapped),
                "带 PEM 头的私钥也要能识别类型");

        String cleaned = YeepayPrivateKeySupport.clean(wrapped);
        assertFalse(cleaned.contains("BEGIN"), "清洗后不应残留 PEM 头");
        assertFalse(cleaned.contains("\n"), "清洗后不应残留换行");
        assertNotNull(YeepayPrivateKeySupport.parse(wrapped, CertTypeEnum.SM2),
                "带 PEM 头的 SM2 私钥应能解析");
    }

    @Test
    @DisplayName("非法内容不抛异常，回落 RSA2048（真正失败在解析阶段暴露）")
    void fallsBackForUnrecognizedContent() {
        assertEquals(CertTypeEnum.RSA2048, YeepayPrivateKeySupport.detectCertType("not-a-key"),
                "无法识别时应回落 RSA2048，保持历史兼容");
        assertEquals(CertTypeEnum.RSA2048, YeepayPrivateKeySupport.detectCertType(null));
    }
}
