package com.wuling.trade.pay.wxpay;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder;
import com.wechat.pay.java.core.http.HttpClient;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.NotificationParser;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 微信支付 SDK Bean 装配（第 14 期支付接入）。
 *
 * <p><b>关键设计：整个配置类由 {@code app.pay.channel=wxpay} 条件装配。</b>
 * 通道为默认的 {@code mock} 时，本类不生效，以下 Bean <b>全部不会被创建</b>：
 * <ul>
 *   <li>不读取任何证书文件（文件不存在也不会报错）</li>
 *   <li>不发起到 {@code api.mch.weixin.qq.com} 的任何网络请求
 *       （{@code RSAAutoCertificateConfig} 构造时会下载平台证书，
 *       这正是必须靠条件装配隔离的原因）</li>
 * </ul>
 * 因此当前「代码已写好但未接入」的状态下，对现有链路零影响。
 *
 * <p><b>两种验签模式</b>：
 * <ul>
 *   <li>{@code public-key}（默认，新商户推荐）：使用商户平台申请下来的
 *       「微信支付公钥」+ 公钥 ID 验签回调解密，无需证书轮换；</li>
 *   <li>{@code platform-cert}：由 SDK 通过 {@code GET /v3/certificates}
 *       自动下载平台证书并定时轮换，需商户 API 证书具备相应权限。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.pay.wxpay.channel", havingValue = "wxpay")
public class WxPaySdkConfig {

    private static final Logger log = LoggerFactory.getLogger(WxPaySdkConfig.class);

    private final WxPayProperties properties;

    public WxPaySdkConfig(WxPayProperties properties) {
        this.properties = properties;
    }

    /**
     * 商户调用凭证配置（请求签名 + 应答验签 + AES 解密）。
     *
     * <p>两者返回类型都是 {@link Config}，但内部验签器不同；
     * 用两个 {@code @Bean} 名区分，避免注入歧义。
     */
    @Bean
    public Config wxPayConfig() {
        if (WxPayProperties.MODE_PLATFORM_CERT.equalsIgnoreCase(properties.getVerifyMode())) {
            return new RSAAutoCertificateConfig.Builder()
                    .merchantId(properties.getMchId())
                    .privateKeyFromPath(properties.getPrivateKeyPath())
                    .merchantSerialNumber(properties.getMerchantSerialNo())
                    .apiV3Key(properties.getApiV3Key())
                    .build();
        }
        return new RSAPublicKeyConfig.Builder()
                .merchantId(properties.getMchId())
                .privateKeyFromPath(properties.getPrivateKeyPath())
                .merchantSerialNumber(properties.getMerchantSerialNo())
                .apiV3Key(properties.getApiV3Key())
                .publicKeyId(properties.getPublicKeyId())
                .publicKeyFromPath(properties.getPublicKeyPath())
                .build();
    }

    /**
     * 回调验签 / 解密配置。
     *
     * <p>与 {@link #wxPayConfig()} 分开声明：回调验签只需要「微信侧公钥 + APIv3 密钥」，
     * 不需要商户私钥；显式分开能避免日后误把商户私钥用于验签。
     */
    @Bean
    public NotificationConfig wxPayNotificationConfig() {
        if (WxPayProperties.MODE_PLATFORM_CERT.equalsIgnoreCase(properties.getVerifyMode())) {
            // 平台证书模式：SDK 依据商户凭证调用 GET /v3/certificates 拉取并轮换平台证书，
            // 故此处同样需要商户号 / 私钥 / 证书序列号
            return new RSAAutoCertificateConfig.Builder()
                    .merchantId(properties.getMchId())
                    .privateKeyFromPath(properties.getPrivateKeyPath())
                    .merchantSerialNumber(properties.getMerchantSerialNo())
                    .apiV3Key(properties.getApiV3Key())
                    .build();
        }
        return new RSAPublicKeyNotificationConfig.Builder()
                .apiV3Key(properties.getApiV3Key())
                .publicKeyId(properties.getPublicKeyId())
                .publicKeyFromPath(properties.getPublicKeyPath())
                .build();
    }

    /** 回调解析器：验签 + AES-GCM 解密一步到位 */
    @Bean
    public NotificationParser wxPayNotificationParser(NotificationConfig wxPayNotificationConfig) {
        return new NotificationParser(wxPayNotificationConfig);
    }

    /** HTTP 客户端：统一超时；开启多域名重试以提升可用性 */
    @Bean
    public HttpClient wxPayHttpClient(Config wxPayConfig) {
        return new DefaultHttpClientBuilder()
                .config(wxPayConfig)
                .connectTimeoutMs(properties.getHttpTimeoutMs())
                .readTimeoutMs(properties.getHttpTimeoutMs())
                .writeTimeoutMs(properties.getHttpTimeoutMs())
                .enableRetryMultiDomain()
                .build();
    }

    /** JSAPI 服务扩展：统一下单 / 查询 / 关单（已内置小程序二次签名） */
    @Bean
    public JsapiServiceExtension wxPayJsapiService(HttpClient wxPayHttpClient) {
        log.info("微信支付 JsapiServiceExtension 已装配 verifyMode={}", properties.getVerifyMode());
        return new JsapiServiceExtension.Builder()
                .httpClient(wxPayHttpClient)
                .build();
    }
}
