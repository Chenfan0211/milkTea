package com.wuling.trade.subscribe;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 微信小程序订阅消息配置（app.wx.*）。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.wx")
public class WxMiniappSubscribeProperties {

    private String appId = "";

    @ToString.Exclude
    private String appSecret = "";

    private String accessTokenUrl = "https://api.weixin.qq.com/cgi-bin/token";

    private String subscribeSendUrl = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    private int httpTimeoutMs = 3000;

    private OrderSubscribe orderSubscribe = new OrderSubscribe();

    private MallShippedSubscribe mallShippedSubscribe = new MallShippedSubscribe();

    @PostConstruct
    public void validate() {
        if (orderSubscribe.isEnabled()
                && (!StringUtils.hasText(appId)
                || !StringUtils.hasText(appSecret)
                || !StringUtils.hasText(orderSubscribe.getTemplateId()))) {
            throw new IllegalStateException("下单订阅消息已启用但 appId/appSecret/templateId 配置不完整");
        }
    }

    @Data
    public static class OrderSubscribe {
        private boolean enabled = true;
        private String templateId = "bxgB0GhwNBcC37qRILiRKOx5rxJWn7Phuw5ZaNB8lEI";
        private String page = "pages/order-detail/order-detail";
        private String miniprogramState = "formal";
    }

    @Data
    public static class MallShippedSubscribe {
        private boolean enabled = true;
        private String templateId = "";
        private String page = "packageMall/pages/order-detail/order-detail";
        private String miniprogramState = "formal";
    }
}
