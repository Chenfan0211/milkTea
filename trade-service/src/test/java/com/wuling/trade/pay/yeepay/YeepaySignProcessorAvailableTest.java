package com.wuling.trade.pay.yeepay;

import com.yeepay.yop.sdk.auth.credentials.PKICredentialsItem;
import com.yeepay.yop.sdk.auth.signer.process.YopSignProcessor;
import com.yeepay.yop.sdk.security.CertTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证易宝 SDK 的签名处理器在当前 classpath 下按需可用，且能真正签出签名串。
 *
 * <p><b>为什么单独测这个</b>：私钥能解析 ≠ 能签名。SDK 通过
 * {@code META-INF/services/com.yeepay.yop.sdk.auth.signer.process.YopSignProcessor}
 * 以 SPI 方式发现签名实现：
 * <ul>
 *   <li>{@code yop-java-sdk-crypto-inter} 提供 {@code YopRsaSignProcessor}</li>
 *   <li>{@code yop-java-sdk-crypto-gm} 提供 {@code YopSm2SignProcessor}</li>
 * </ul>
 * 如果只加了依赖但 SPI 没生效（比如被 shade/裁剪），会在真实调用易宝时
 * 才炸「找不到签名器」，那时排查成本极高。这里提前在测试阶段拦住。
 */
class YeepaySignProcessorAvailableTest {

    private static final String SM2_PRIVATE_KEY =
            "MIGTAgEAMBMGByqGSM49AgEGCCqBHM9VAYItBHkwdwIBAQQgGVRDhEATzRLrICn/B2tUEgKOYz6PDk2fDlJnRaiuD/2"
                    + "gCgYIKoEcz1UBgi2hRANCAASmrnhdw74jYdX4niyb/1638pNiJDoOc0k5v9pyfzr+DbqJ5d3SqEzR6ynEgfK/crz"
                    + "8bqkoGe7ccSFrceT1nREe";

    @Test
    @DisplayName("SPI 同时注册了 RSA 与 SM2 签名处理器")
    void bothSignProcessorsRegistered() {
        List<String> names = new ArrayList<>();
        for (YopSignProcessor processor : ServiceLoader.load(YopSignProcessor.class)) {
            names.add(processor.getClass().getSimpleName());
        }

        assertTrue(names.stream().anyMatch(n -> n.contains("Rsa")),
                "未找到 RSA 签名处理器（yop-java-sdk-crypto-inter 是否缺失？）：" + names);
        assertTrue(names.stream().anyMatch(n -> n.contains("Sm2") || n.contains("SM2")),
                "未找到 SM2 签名处理器（yop-java-sdk-crypto-gm 是否缺失？）：" + names);
    }

    @Test
    @DisplayName("SM2 签名处理器认同 SM2 凭据，并真正签出非空签名")
    void sm2ProcessorCanSign() {
        PrivateKey key = YeepayPrivateKeySupport.parse(SM2_PRIVATE_KEY, CertTypeEnum.SM2);
        assertNotNull(key, "SM2 私钥解析失败");
        PKICredentialsItem credentials = new PKICredentialsItem(key, CertTypeEnum.SM2);

        YopSignProcessor sm2 = null;
        for (YopSignProcessor processor : ServiceLoader.load(YopSignProcessor.class)) {
            if (processor.getClass().getSimpleName().contains("Sm2")) {
                sm2 = processor;
                break;
            }
        }
        assertNotNull(sm2, "SM2 签名处理器不可用");

        assertTrue(sm2.isSupport(credentials),
                "SM2 签名处理器应支持 SM2 凭据，实际 name=" + sm2.name());

        String signature = sm2.sign("app_10094363805&hello-yeepay&", credentials);
        assertNotNull(signature, "签名结果不应为 null");
        assertFalse(signature.isBlank(), "签名结果不应为空字符串");

        assertNotNull(sm2.getDigestAlg(), "摘要算法不应为空");
        assertTrue(sm2.getDigestAlg().contains("SM3"),
                "SM2 签名应使用 SM3 摘要，实际=" + sm2.getDigestAlg());
    }
}
