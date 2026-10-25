package com.wuling.marketing.subscribe;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 营销服务微信订阅消息配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.wx")
public class WxMarketingSubscribeProperties {

    private String appId = "";
    @ToString.Exclude
    private String appSecret = "";
    private String accessTokenUrl = "https://api.weixin.qq.com/cgi-bin/token";
    private String subscribeSendUrl = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";
    private int httpTimeoutMs = 3000;

    private Subscribe subscribe = new Subscribe();
    private CouponExpiryJob couponExpiry = new CouponExpiryJob();

    @PostConstruct
    public void validate() {
        if (!subscribe.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(appId) || !StringUtils.hasText(appSecret)) {
            throw new IllegalStateException("营销订阅消息已启用但 appId/appSecret 配置不完整");
        }
        validateTemplate("pointsChange", subscribe.getPointsChange());
        validateTemplate("pointsExchange", subscribe.getPointsExchange());
        validateTemplate("storedValueRecharge", subscribe.getStoredValueRecharge());
        validateTemplate("balanceChange", subscribe.getBalanceChange());
        validateTemplate("couponArrival", subscribe.getCouponArrival());
        validateTemplate("couponExpiry", subscribe.getCouponExpiry());
    }

    private void validateTemplate(String key, TemplateConfig config) {
        if (config.isEnabled() && !StringUtils.hasText(config.getTemplateId())) {
            throw new IllegalStateException("营销订阅消息已启用但缺少模板 ID: " + key);
        }
    }

    public TemplateConfig template(String key) {
        return switch (key) {
            case "pointsChange" -> subscribe.getPointsChange();
            case "pointsExchange" -> subscribe.getPointsExchange();
            case "storedValueRecharge" -> subscribe.getStoredValueRecharge();
            case "balanceChange" -> subscribe.getBalanceChange();
            case "couponArrival" -> subscribe.getCouponArrival();
            case "couponExpiry" -> subscribe.getCouponExpiry();
            default -> throw new IllegalArgumentException("未知订阅模板: " + key);
        };
    }

    @Data
    public static class Subscribe {
        private boolean enabled = true;
        private TemplateConfig pointsChange = new TemplateConfig();
        private TemplateConfig pointsExchange = new TemplateConfig();
        private TemplateConfig storedValueRecharge = new TemplateConfig();
        private TemplateConfig balanceChange = new TemplateConfig();
        private TemplateConfig couponArrival = new TemplateConfig();
        private TemplateConfig couponExpiry = new TemplateConfig();
    }

    @Data
    public static class TemplateConfig {
        private boolean enabled = true;
        private String templateId = "";
        private String page = "";
        private String miniprogramState = "formal";
    }

    @Data
    public static class CouponExpiryJob {
        private boolean enabled = true;
        private String cron = "0 0 10 * * ?";
        private int daysBefore = 3;
        private int batchSize = 500;
    }
}
