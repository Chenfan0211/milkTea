package com.wuling.user.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * 腾讯云短信通道（占位实现）。
 *
 * 说明：腾讯云 SMS 需要 TC3-HMAC-SHA256 签名，涉及 SDKAppID / SecretId / SecretKey /
 * 签名内容 / 模板 ID。当前项目尚未提供服务商密钥，因此这里只保留结构：
 * - 未配置密钥时发送失败并记录日志（不会静默成功，避免误判）；
 * - 拿到密钥后在此补全签名与请求逻辑，或直接引入 tencentcloud-sdk-java 实现。
 *
 * 启用条件：app.sms.enabled=true 且配置了 app.sms.secret-id。
 */
@Component
@ConditionalOnProperty(name = "app.sms.enabled", havingValue = "true")
public class TencentSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentSmsProvider.class);
    private static final String ENDPOINT = "https://sms.tencentcloudapi.com";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private final String sdkAppId;
    private final String secretId;
    private final String secretKey;
    private final String signName;
    private final String templateId;

    public TencentSmsProvider(@Value("${app.sms.sdk-app-id:}") String sdkAppId,
                              @Value("${app.sms.secret-id:}") String secretId,
                              @Value("${app.sms.secret-key:}") String secretKey,
                              @Value("${app.sms.sign-name:}") String signName,
                              @Value("${app.sms.template-id:}") String templateId) {
        this.sdkAppId = sdkAppId;
        this.secretId = secretId;
        this.secretKey = secretKey;
        this.signName = signName;
        this.templateId = templateId;
    }

    @Override
    public String channel() {
        return "tencent";
    }

    @Override
    public boolean send(String phone, String code) {
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey)
                || !StringUtils.hasText(sdkAppId) || !StringUtils.hasText(templateId)) {
            log.error("腾讯云短信未配置完整（sdkAppId/secretId/secretKey/templateId），无法发送");
            return false;
        }
        // TODO 接入腾讯云 SMS：此处需实现 TC3-HMAC-SHA256 签名后调用 SendSms
        log.error("腾讯云短信发送尚未实现（需补全 TC3 签名）：phone={} endpoint={}", phone, ENDPOINT);
        return false;
    }
}
