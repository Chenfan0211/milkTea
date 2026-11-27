package com.wuling.trade.pay.yeepay;

import com.yeepay.yop.sdk.auth.credentials.PKICredentialsItem;
import com.yeepay.yop.sdk.auth.credentials.YopPKICredentials;
import com.yeepay.yop.sdk.auth.credentials.provider.YopCredentialsProvider;
import com.yeepay.yop.sdk.security.CertTypeEnum;
import com.yeepay.yop.sdk.service.common.YopClient;
import com.yeepay.yop.sdk.service.common.YopClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.PrivateKey;
import java.util.List;

/**
 * 易宝 YOP SDK 装配。
 *
 * <p><b>为什么是懒加载代理</b>：配置现在来自数据库
 * （{@code platform_profile.pay_config}），由 {@code YeepayConfigLoader}
 * 在 {@code ApplicationReadyEvent} 时加载。而 {@code @Bean} 方法在容器
 * 刷新期（更早）就会执行——那时 appKey / 私钥还是空的。
 *
 * <p>因此这里返回一个<b>延迟创建</b>的代理：首次真正调用易宝接口时
 * 才根据当前配置构建 {@code YopClient}，并缓存复用。
 *
 * <p><b>条件装配</b>：仅 {@code app.pay.yeepay.channel=yeepay} 时创建。
 * 未启用时一个易宝 Bean 都不装配，线上零影响。
 *
 * <p><b>支持 RSA2048 与 SM2（国密）两种私钥</b>：
 * <ul>
 *   <li>密钥类型不靠人工配置，而是<b>按私钥内容自动识别</b>——运营从易宝后台
 *       下载的是哪种就是哪种，配错类型不会静默失败；</li>
 *   <li>SM2 依赖 {@code yop-java-sdk-crypto-gm}（见 trade-service/pom.xml），
 *       该包通过 SPI 注册 {@code YopSm2PrivateKeyParser} 与
 *       {@code YopSm2SignProcessor}；RSA 则走 {@code yop-java-sdk-crypto-inter}。
 *       两个包同时存在，由 {@code CertTypeEnum} 在运行时选择。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.pay.yeepay.channel", havingValue = "yeepay")
public class YeepaySdkConfig {

    private static final Logger log = LoggerFactory.getLogger(YeepaySdkConfig.class);

    private final YeepayProperties properties;

    public YeepaySdkConfig(YeepayProperties properties) {
        this.properties = properties;
    }

    /**
     * 延迟初始化的 YopClient。
     *
     * <p>Spring 注入的是 {@link LazyYopClient} 包装对象；它把
     * {@code request(...)} 转发给首次调用时创建的真正客户端。
     *
     * <p>配置缺失时首次调用会抛异常（而不是静默失败），
     * 由调用方（{@code YeepayApiClient}）转成业务错误。
     */
    @Bean
    public YopClient yopClient() {
        return new LazyYopClient(properties);
    }

    /**
     * 延迟创建 + 缓存的 YopClient 包装。
     *
     * <p><b>为什么缓存</b>：YopClient 内部持有 HttpClient 连接池，
     * 每次请求都新建会耗尽文件句柄且极慢。
     *
     * <p><b>线程安全</b>：用 {@code synchronized} 保护首次创建。
     * 创建是低频操作（只在启动后第一次请求时发生），
     * 无需更复杂的双检锁。
     */
    static class LazyYopClient implements YopClient {

        private final YeepayProperties properties;
        private volatile YopClient delegate;

        LazyYopClient(YeepayProperties properties) {
            this.properties = properties;
        }

        @Override
        public com.yeepay.yop.sdk.service.common.response.YopResponse request(
                com.yeepay.yop.sdk.service.common.request.YopRequest request) {
            return delegate().request(request);
        }

        @Override
        public com.yeepay.yop.sdk.service.common.response.YosUploadResponse upload(
                com.yeepay.yop.sdk.service.common.request.YopRequest request) {
            return delegate().upload(request);
        }

        @Override
        public com.yeepay.yop.sdk.model.yos.YosDownloadResponse download(
                com.yeepay.yop.sdk.service.common.request.YopRequest request) {
            return delegate().download(request);
        }

        private YopClient delegate() {
            YopClient local = delegate;
            if (local != null) {
                return local;
            }
            synchronized (this) {
                if (delegate == null) {
                    delegate = build();
                }
                return delegate;
            }
        }

        private YopClient build() {
            String appKey = properties.getAppKey();
            String privateKeyText = properties.getPrivateKey();
            if (appKey == null || appKey.isBlank()) {
                throw new IllegalStateException(
                        "易宝 appKey 未配置。请在运营后台「平台主体」页填写易宝应用标识后重启服务。");
            }
            if (privateKeyText == null || privateKeyText.isBlank()) {
                throw new IllegalStateException(
                        "易宝商户私钥未配置。请在运营后台「平台主体」页填写商户私钥后重启服务。");
            }

            CertTypeEnum certType = YeepayPrivateKeySupport.detectCertType(privateKeyText);
            PrivateKey privateKey;
            try {
                privateKey = YeepayPrivateKeySupport.parse(privateKeyText, certType);
            } catch (Exception e) {
                assertConfigured("易宝商户私钥", e);
                throw new IllegalStateException(
                        "易宝商户私钥解析失败（识别为 " + certType + "）。"
                                + "请确认从易宝开放平台下载的是与证书类型匹配的私钥，且未被截断。", e);
            }
            if (privateKey == null) {
                throw new IllegalStateException(
                        "易宝商户私钥解析失败（识别为 " + certType + "，解析结果为空）");
            }

            YopCredentialsProvider credentialsProvider =
                    new FixedCredentialsProvider(appKey, privateKey, certType);
            YopClient client = YopClientBuilder.builder()
                    .withCredentialsProvider(credentialsProvider)
                    .build();
            log.info("易宝 YopClient 已创建 appKey={} certType={} sandbox={}",
                    maskAppKey(appKey), certType, properties.isSandbox());
            return client;
        }

        /** 把反射/SPI 缺失这类「环境问题」转成可操作的提示，而不是暴露堆栈。 */
        private void assertConfigured(String what, Throwable cause) {
            String msg = cause == null ? "" : String.valueOf(cause.getMessage());
            if (msg.contains("SM2") || msg.contains("NoSuchAlgorithm")) {
                log.error("易宝 {} 为 SM2 国密类型，但国密算法包缺失或被裁剪。"
                        + "需确保 trade-service 依赖 yop-java-sdk-crypto-gm。", what);
            }
        }

        private String maskAppKey(String appKey) {
            if (appKey == null || appKey.length() <= 8) {
                return "***";
            }
            return appKey.substring(0, 8) + "***";
        }
    }

    /**
     * 固定凭据提供者：把配置里的 appKey + 私钥直接交给 SDK。
     *
     * <p>实现 {@link YopCredentialsProvider} 而非继承文件版实现，
     * 是因为文件版会去读 classPath 的 json 配置文件——
     * 我们恰恰要绕开文件，全部走数据库配置。
     *
     * <p><b>certType 必须如实传入</b>：SDK 依据它选择签名处理器
     * （RSA2048 → YopRsaSignProcessor，SM2 → YopSm2SignProcessor）。
     * 传错会导致签名结果不被易宝接受。
     */
    private static class FixedCredentialsProvider implements YopCredentialsProvider {

        private final YopPKICredentials credentials;
        private final List<CertTypeEnum> supportedCertTypes;

        FixedCredentialsProvider(String appKey, PrivateKey privateKey, CertTypeEnum certType) {
            this.credentials = new YopPKICredentials(appKey,
                    new PKICredentialsItem(privateKey, certType));
            this.supportedCertTypes = List.of(certType);
        }

        @Override
        public YopPKICredentials getCredentials(String appKey, String provider) {
            return credentials;
        }

        @Override
        public YopPKICredentials getCredentials(String appKey, String provider, String env, String credentialType) {
            return credentials;
        }

        @Override
        public List<CertTypeEnum> getSupportCertTypes(String appKey) {
            return supportedCertTypes;
        }

        @Override
        public List<CertTypeEnum> getSupportCertTypes(String appKey, String provider, String env) {
            return supportedCertTypes;
        }
    }
}
