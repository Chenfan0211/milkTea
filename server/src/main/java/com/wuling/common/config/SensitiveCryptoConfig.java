package com.wuling.common.config;

import com.wuling.common.security.SensitiveCrypto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 敏感字段加密初始化。
 *
 * <p><b>为什么在启动时初始化而非首次使用时懒加载</b>：没有密钥时应当
 * <b>尽早失败</b>（配置错误在启动阶段暴露），而不是等第一个用户提交
 * 身份证号时才抛异常——那时用户已经填完表单，体验很差。
 *
 * <p><b>为什么不提供默认密钥</b>：默认密钥等于没加密，反而给人虚假安全感。
 * 未配置时加密方法直接抛异常，属于「宁可拒绝入库也不明文落盘」的 fail-closed。
 *
 * <p><b>未配置不影响现有功能</b>：微信支付链路不涉及敏感字段加密，
 * 因此密钥留空时服务正常启动，只有调用入驻/绑卡接口才会报错提示配置密钥。
 */
@Configuration
public class SensitiveCryptoConfig {

    private static final Logger log = LoggerFactory.getLogger(SensitiveCryptoConfig.class);

    @Value("${app.sensitive.key:}")
    private String sensitiveKey;

    @PostConstruct
    public void init() {
        if (sensitiveKey == null || sensitiveKey.isBlank()) {
            log.warn("未配置 app.sensitive.key（APP_SENSITIVE_KEY）："
                    + "商户入驻与结算卡绑定将因无法加密证件号/卡号而拒绝入库。"
                    + "如需启用易宝入驻功能，请注入 Base64 编码的 32 字节密钥。");
            return;
        }
        SensitiveCrypto.initialize(sensitiveKey);
    }
}
