package com.wuling.trade.subscribe;

import lombok.Data;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 微信小程序订阅消息配置（app.wx.*）。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.wx")
public class WxMiniappSubscribeProperties {

    private static final Logger log = LoggerFactory.getLogger(WxMiniappSubscribeProperties.class);

    private String appId = "";

    @ToString.Exclude
    private String appSecret = "";

    private String accessTokenUrl = "https://api.weixin.qq.com/cgi-bin/token";

    private String subscribeSendUrl = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    private int httpTimeoutMs = 3000;

    private OrderSubscribe orderSubscribe = new OrderSubscribe();

    private MallShippedSubscribe mallShippedSubscribe = new MallShippedSubscribe();

    /**
     * 启动完成后校验配置。
     *
     * <p>必须用 ApplicationReadyEvent 而不是 PostConstruct：
     * ConfigurationProperties 的绑定发生在 Bean 初始化之后，
     * 在 PostConstruct 中读到的仍是字段默认值，无法反映真实外部配置
     * （历史问题：发货模板缺失时完全静默，用户收不到发货通知且无人知晓）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        log.debug("订阅消息配置校验 appIdSet={} appSecretSet={} orderTemplateSet={} mallShippedTemplateSet={} mallShippedEnabled={}",
                StringUtils.hasText(appId), StringUtils.hasText(appSecret),
                StringUtils.hasText(orderSubscribe.getTemplateId()),
                StringUtils.hasText(mallShippedSubscribe.getTemplateId()),
                mallShippedSubscribe.isEnabled());
        if (orderSubscribe.isEnabled()
                && (!StringUtils.hasText(appId)
                || !StringUtils.hasText(appSecret)
                || !StringUtils.hasText(orderSubscribe.getTemplateId()))) {
            throw new IllegalStateException("下单订阅消息已启用但 appId/appSecret/templateId 配置不完整");
        }
        // 发货通知降级为告警：不阻断启动，但必须让运维看见。
        if (mallShippedSubscribe.isEnabled() && !StringUtils.hasText(mallShippedSubscribe.getTemplateId())) {
            log.warn("商城发货订阅模板未配置（app.wx.mall-shipped-subscribe.template-id 为空），发货通知将被跳过");
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
