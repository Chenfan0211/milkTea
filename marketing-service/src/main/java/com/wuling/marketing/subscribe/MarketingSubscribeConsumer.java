package com.wuling.marketing.subscribe;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/** 营销订阅消息消费者。 */
@Component
public class MarketingSubscribeConsumer extends AbstractMqConsumer {

    private final SubscribeMessageLedger ledger;
    private final WxMarketingSubscribeProperties properties;
    private final StoreNameResolver storeNameResolver;
    private final AppUserMapper appUserMapper;
    private final SubscribeMessageSender sender;
    private final MarketingSubscribeMessageFactory factory;

    public MarketingSubscribeConsumer(MqIdempotent idempotent,
                                      SubscribeMessageLedger ledger,
                                      WxMarketingSubscribeProperties properties,
                                      StoreNameResolver storeNameResolver,
                                      AppUserMapper appUserMapper,
                                      SubscribeMessageSender sender,
                                      MarketingSubscribeMessageFactory factory) {
        super(idempotent);
        this.ledger = ledger;
        this.properties = properties;
        this.storeNameResolver = storeNameResolver;
        this.appUserMapper = appUserMapper;
        this.sender = sender;
        this.factory = factory;
    }

    @RabbitListener(queues = MqConstants.SUBSCRIBE_MARKETING_QUEUE)
    public void onMessage(Message message, Channel channel) {
        consumeAfterSuccess(message, channel, this::handle);
    }

    private void handle(Message message) throws Exception {
        SubscribeMessageEvent event = objectMapper.readValue(message.getBody(), SubscribeMessageEvent.class);
        if (!StringUtils.hasText(event.messageKey()) || !StringUtils.hasText(event.templateKey())) {
            throw new IllegalArgumentException("营销订阅事件缺少 messageKey/templateKey");
        }
        if (ledger.isTerminal(event.messageKey())) {
            return;
        }

        WxMarketingSubscribeProperties.TemplateConfig config = properties.template(event.templateKey());
        if (config == null || !properties.getSubscribe().isEnabled() || !config.isEnabled()) {
            ledger.markSkipped(event.messageKey(), -1, "模板已关闭或不存在");
            return;
        }

        String storeName = null;
        if (requiresStore(event.templateKey())) {
            storeName = storeNameResolver.resolve(event.storeSubjectId());
            if (!StringUtils.hasText(storeName)) {
                ledger.markSkipped(event.messageKey(), -1, "门店不存在或已失效");
                return;
            }
        }

        AppUser user = appUserMapper.selectById(event.userId());
        if (user == null || !StringUtils.hasText(user.getOpenId())) {
            ledger.markSkipped(event.messageKey(), -1, "用户 openid 不存在");
            return;
        }

        Map<String, Map<String, String>> data = factory.build(event.templateKey(), storeName, event.values());
        SubscribeMessageSender.SendResult result = sender.send(
                user.getOpenId(), config, data, event.page());
        if (result.errcode() == 0) {
            ledger.markSent(event.messageKey(), result.errcode(), result.errmsg());
            return;
        }
        if (result.errcode() == 43101) {
            ledger.markSkipped(event.messageKey(), result.errcode(), result.errmsg());
            return;
        }
        ledger.markFailed(event.messageKey(), result.errcode(), result.errmsg());
        throw new IllegalStateException("营销订阅消息发送失败 errcode=" + result.errcode());
    }

    private boolean requiresStore(String templateKey) {
        return "pointsChange".equals(templateKey)
                || "storedValueRecharge".equals(templateKey)
                || "balanceChange".equals(templateKey);
    }
}
