package com.wuling.trade.pay.wxpay;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 微信支付 APIv3 配置（第 14 期支付接入）。
 *
 * <p><b>本类不持有任何密钥的默认值</b>：所有敏感项均由部署环境注入
 * （环境变量或挂载文件），与既有 {@code PAY_CALLBACK_SECRET} 的处理方式一致。
 *
 * <p><b>fail-fast 语义（重要）</b>：
 * <ol>
 *   <li>{@link #validateOnStartup()} 只在 {@code app.pay.channel=wxpay} 时强校验；
 *       通道为 {@code mock} 时（当前默认）不校验，保证本地开发与 CI
 *       在没有商户号的情况下照常启动；</li>
 *   <li>一旦切到 {@code wxpay} 而缺任何一项配置/证书文件，<b>服务拒绝启动</b> ——
 *       绝不能带着「无签名能力」的支付通道上线。</li>
 * </ol>
 *
 * <p><b>为什么当前可以先「写好但不生效」</b>：
 * 本项目的域名为 {@code api.wulingshiguang.top}，备案通过前无法作为
 * 微信支付回调地址（微信要求 https + 已备案域名）。因此默认通道保持
 * {@code mock}，本类只做「配置就绪度」校验，不发起任何网络调用。
 */
@Component
@ConfigurationProperties(prefix = "app.pay.wxpay")
public class WxPayProperties {

    private static final Logger log = LoggerFactory.getLogger(WxPayProperties.class);

    /** 支付通道：mock（默认）| wxpay */
    private String channel = "mock";

    /** 商户号 */
    private String mchId;

    /** 小程序 AppID（须与商户号完成绑定） */
    private String appId;

    /** APIv3 密钥（32 位），用于解密回调 resource 与敏感字段 */
    private String apiV3Key;

    /** 商户 API 证书序列号（请求签名用） */
    private String merchantSerialNo;

    /** 商户私钥文件路径（apiclient_key.pem） */
    private String privateKeyPath;

    /**
     * 验签模式：
     * {@code public-key}（微信支付公钥，新商户推荐）| {@code platform-cert}（平台证书，SDK 自动轮换）。
     */
    private String verifyMode = "public-key";

    /** 微信支付公钥 ID（形如 PUB_KEY_ID_xxx），verify-mode=public-key 时必填 */
    private String publicKeyId;

    /** 微信支付公钥文件路径（pub_key.pem），verify-mode=public-key 时必填 */
    private String publicKeyPath;

    /** 支付结果通知地址：必须是已备案的 https 域名 */
    private String notifyUrl;

    /** 微信支付 API 基地址，可覆盖以便本地用「假微信服务端」做端到端验证 */
    private String apiBaseUrl = "https://api.mch.weixin.qq.com";

    /** 回调时间戳允许漂移窗口（秒），默认 5 分钟，防重放 */
    private long notifyToleranceSeconds = 300;

    /** HTTP 超时（毫秒） */
    private int httpTimeoutMs = 8000;

    /** 商品描述前缀（用户看到的收银台标题） */
    private String descriptionPrefix = "五零时光";

    public static final String CHANNEL_WXPAY = "wxpay";
    public static final String MODE_PUBLIC_KEY = "public-key";
    public static final String MODE_PLATFORM_CERT = "platform-cert";

    /**
     * 启动期校验：仅当通道为 wxpay 时强制校验，mock 通道直接跳过。
     *
     * <p>报错信息里带上对应的<b>环境变量名</b>，避免上线时靠猜。
     */
    @PostConstruct
    public void validateOnStartup() {
        if (!isWxPayChannel()) {
            // mock 通道：不校验任何微信支付配置（本地开发 / CI / 当前生产状态）
            return;
        }

        requireText(mchId, "app.pay.wxpay.mch-id", "WXPAY_MCH_ID");
        requireText(appId, "app.pay.wxpay.app-id", "WXPAY_APP_ID");
        requireText(apiV3Key, "app.pay.wxpay.api-v3-key", "WXPAY_API_V3_KEY");
        requireText(merchantSerialNo, "app.pay.wxpay.merchant-serial-no", "WXPAY_MCH_SERIAL_NO");
        requireReadableFile(privateKeyPath, "app.pay.wxpay.private-key-path", "WXPAY_PRIVATE_KEY_PATH");
        requireText(notifyUrl, "app.pay.wxpay.notify-url", "WXPAY_NOTIFY_URL");

        if (apiV3Key.trim().length() != 32) {
            throw new IllegalStateException(
                    "app.pay.wxpay.api-v3-key（环境变量 WXPAY_API_V3_KEY）长度必须为 32 位字符，"
                    + "当前为 " + apiV3Key.trim().length() + " 位。该密钥在商户平台「账户中心-API安全」设置。");
        }

        if (MODE_PUBLIC_KEY.equalsIgnoreCase(verifyMode)) {
            requireText(publicKeyId, "app.pay.wxpay.public-key-id", "WXPAY_PUBLIC_KEY_ID");
            requireReadableFile(publicKeyPath, "app.pay.wxpay.public-key-path", "WXPAY_PUBLIC_KEY_PATH");
        } else if (MODE_PLATFORM_CERT.equalsIgnoreCase(verifyMode)) {
            // 平台证书模式下由 SDK 通过 GET /v3/certificates 自动下载并轮换，无需本地文件
            log.info("微信支付验签模式为 platform-cert，平台证书将由 SDK 自动下载与轮换");
        } else {
            throw new IllegalStateException(
                    "app.pay.wxpay.verify-mode 取值非法：" + verifyMode
                    + "，只支持 public-key（微信支付公钥）或 platform-cert（平台证书）。");
        }

        if (!notifyUrl.toLowerCase().startsWith("https://")) {
            throw new IllegalStateException(
                    "app.pay.wxpay.notify-url（环境变量 WXPAY_NOTIFY_URL）必须是 https 地址，"
                    + "微信支付要求回调地址为已备案域名且启用 HTTPS。当前值：" + notifyUrl);
        }

        log.info("微信支付通道已启用 mchId={} verifyMode={} apiBaseUrl={}", mchId, verifyMode, apiBaseUrl);
    }

    /** 是否启用真实微信支付通道 */
    public boolean isWxPayChannel() {
        return CHANNEL_WXPAY.equalsIgnoreCase(channel);
    }

    private void requireText(String value, String key, String env) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "当前支付通道为 wxpay（app.pay.channel=wxpay），但未配置 " + key
                    + "（环境变量 " + env + "），服务拒绝启动。");
        }
    }

    private void requireReadableFile(String pathText, String key, String env) {
        if (!StringUtils.hasText(pathText)) {
            throw new IllegalStateException(
                    "当前支付通道为 wxpay，但未配置 " + key + "（环境变量 " + env + "），服务拒绝启动。");
        }
        try {
            Path path = Paths.get(pathText.trim());
            if (!Files.isReadable(path)) {
                throw new IllegalStateException(
                        "配置项 " + key + "（环境变量 " + env + "）指向的文件不可读：" + pathText
                        + "。请确认证书已落盘且运行用户具备读权限（建议 chmod 600、属主与 systemd 一致）。");
            }
        } catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "配置项 " + key + "（环境变量 " + env + "）不是合法路径：" + pathText, e);
        }
    }

    // ---------- getter / setter ----------

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getMchId() {
        return mchId;
    }

    public void setMchId(String mchId) {
        this.mchId = mchId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getApiV3Key() {
        return apiV3Key;
    }

    public void setApiV3Key(String apiV3Key) {
        this.apiV3Key = apiV3Key;
    }

    public String getMerchantSerialNo() {
        return merchantSerialNo;
    }

    public void setMerchantSerialNo(String merchantSerialNo) {
        this.merchantSerialNo = merchantSerialNo;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getVerifyMode() {
        return verifyMode;
    }

    public void setVerifyMode(String verifyMode) {
        this.verifyMode = verifyMode;
    }

    public String getPublicKeyId() {
        return publicKeyId;
    }

    public void setPublicKeyId(String publicKeyId) {
        this.publicKeyId = publicKeyId;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public void setPublicKeyPath(String publicKeyPath) {
        this.publicKeyPath = publicKeyPath;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public void setNotifyUrl(String notifyUrl) {
        this.notifyUrl = notifyUrl;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    public long getNotifyToleranceSeconds() {
        return notifyToleranceSeconds;
    }

    public void setNotifyToleranceSeconds(long notifyToleranceSeconds) {
        this.notifyToleranceSeconds = notifyToleranceSeconds;
    }

    public int getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(int httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }

    public String getDescriptionPrefix() {
        return descriptionPrefix;
    }

    public void setDescriptionPrefix(String descriptionPrefix) {
        this.descriptionPrefix = descriptionPrefix;
    }
}
