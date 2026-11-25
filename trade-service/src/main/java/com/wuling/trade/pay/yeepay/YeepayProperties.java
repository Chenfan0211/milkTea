package com.wuling.trade.pay.yeepay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 易宝支付配置（{@code app.pay.yeepay.*}）。
 *
 * <p><b>与微信配置完全独立</b>：微信走 {@code app.pay.wxpay.*}，易宝走
 * {@code app.pay.yeepay.*}。两个通道的开关、密钥、回调地址互不影响——
 * 这是「不影响现有微信支付」的核心保障。
 *
 * <p><b>开关语义</b>：{@code app.pay.yeepay.channel=yeepay} 才装配易宝 Bean。
 * 留空时服务正常运行、一个易宝 Bean 都不创建、不发起任何请求。
 *
 * <p><b>密钥注入</b>：全部由部署环境变量注入，仓库内不保存任何真实值。
 * 缺失时只在 {@code channel=yeepay} 的情况下启动失败（fail-fast），
 * 避免「以为在对接，实际空跑」。
 */
@Component
@ConfigurationProperties(prefix = "app.pay.yeepay")
public class YeepayProperties {

    private static final Logger log = LoggerFactory.getLogger(YeepayProperties.class);

    /** 启用开关：值为 yeepay 时才装配 Bean */
    private String channel;

    /** YOP 应用标识 */
    private String appKey;

    /** 服务商主商编 */
    private String merchantNo;

    /** 平台商商编（子商户的 parentMerchantNo，分账发起方） */
    private String platformMerchantNo;

    /** 商户私钥（RSA2048，Base64 或 PEM 均可，由 SDK 决定） */
    private String privateKey;

    /** 易宝平台公钥（验证回调签名） */
    private String yopPublicKey;

    /** 回调基础地址，如 https://api.wulingshiguang.top */
    private String notifyBaseUrl = "https://api.wulingshiguang.top";

    /** 分账调用间隔（毫秒）：易宝要求同一订单两次分账间隔 2-3 秒 */
    private long divideIntervalMs = 2500L;

    /** 是否沙箱环境 */
    private boolean sandbox = false;

    /** HTTP 超时（毫秒） */
    private int httpTimeoutMs = 8000;

    /** 通道开关是否打开（仅看 channel 配置，不看密钥是否齐全）。 */
    public boolean isChannelEnabled() {
        return "yeepay".equalsIgnoreCase(channel == null ? "" : channel.trim());
    }

    /**
     * 易宝是否真正可用（通道已启用 <b>且</b> 必需配置齐全）。
     *
     * <p><b>与 {@link #isChannelEnabled()} 的区别</b>：配置现在来自数据库
     * （见 {@code YeepayConfigLoader}），启动时 {@code @PostConstruct}
     * 阶段还拿不到。因此启动校验只能看 channel 开关，
     * 「是否可用」要等配置加载完成后才有意义。
     *
     * <p>各业务 Bean 用本方法判断，配置未就绪时自然降级为不可用，
     * 不会向易宝发出无效请求。
     */
    public boolean isEnabled() {
        return isChannelEnabled()
                && notBlank(appKey) && notBlank(merchantNo) && notBlank(privateKey);
    }

    private boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * 启动校验：只检查 channel 开关，<b>不</b>校验密钥。
     *
     * <p><b>为什么改</b>：配置已迁到数据库，{@code @PostConstruct} 时
     * 连不上 server、也还没加载配置。此处若 fail-fast，
     * 会让服务在配置就绪前就起不来。
     *
     * <p>配置完整性由 {@code YeepayConfigLoader} 在
     * {@code ApplicationReadyEvent} 时校验并记 ERROR。
     */
    @PostConstruct
    public void validateOnStartup() {
        if (!isChannelEnabled()) {
            log.info("易宝通道未启用（app.pay.yeepay.channel={}）", channel);
            return;
        }
        log.info("易宝通道已启用，等待从数据库加载配置（见 YeepayConfigLoader）");
    }

    /** 组装完整回调地址。 */
    public String notifyUrl(String path) {
        String base = notifyBaseUrl == null ? "" : notifyBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getMerchantNo() {
        return merchantNo;
    }

    public void setMerchantNo(String merchantNo) {
        this.merchantNo = merchantNo;
    }

    public String getPlatformMerchantNo() {
        return platformMerchantNo;
    }

    public void setPlatformMerchantNo(String platformMerchantNo) {
        this.platformMerchantNo = platformMerchantNo;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getYopPublicKey() {
        return yopPublicKey;
    }

    public void setYopPublicKey(String yopPublicKey) {
        this.yopPublicKey = yopPublicKey;
    }

    public String getNotifyBaseUrl() {
        return notifyBaseUrl;
    }

    public void setNotifyBaseUrl(String notifyBaseUrl) {
        this.notifyBaseUrl = notifyBaseUrl;
    }

    public long getDivideIntervalMs() {
        return divideIntervalMs;
    }

    public void setDivideIntervalMs(long divideIntervalMs) {
        this.divideIntervalMs = divideIntervalMs;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    public int getHttpTimeoutMs() {
        return httpTimeoutMs;
    }

    public void setHttpTimeoutMs(int httpTimeoutMs) {
        this.httpTimeoutMs = httpTimeoutMs;
    }
}
