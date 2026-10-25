package com.wuling.marketing.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import com.wuling.user.entity.AppUser;
import com.wuling.user.mapper.AppUserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.LocalDateTime;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingSubscribeConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void resolvesStoreAndSendsFromQueue() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        StoreNameResolver storeNameResolver = mock(StoreNameResolver.class);
        AppUserMapper userMapper = mock(AppUserMapper.class);
        SubscribeMessageSender sender = mock(SubscribeMessageSender.class);
        Channel channel = mock(Channel.class);
        SubscribeMessageEvent event = event();
        AppUser user = new AppUser();
        user.setOpenId("openid-9");
        when(idempotent.isConsumed("evt-1")).thenReturn(false);
        when(ledger.isTerminal("POINTS_CHANGE:1")).thenReturn(false);
        when(storeNameResolver.resolve(101L)).thenReturn("系统门店A");
        when(userMapper.selectById(9L)).thenReturn(user);
        when(sender.send(eq("openid-9"), any(), any(), any()))
                .thenReturn(new SubscribeMessageSender.SendResult(0, "ok"));

        new MarketingSubscribeConsumer(idempotent, ledger, configured(), storeNameResolver,
                userMapper, sender, new MarketingSubscribeMessageFactory())
                .onMessage(message(event, "evt-1", 1L), channel);

        verify(ledger).markSent("POINTS_CHANGE:1", 0, "ok");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void skipsInvalidStoreWithoutSending() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        StoreNameResolver storeNameResolver = mock(StoreNameResolver.class);
        AppUserMapper userMapper = mock(AppUserMapper.class);
        SubscribeMessageSender sender = mock(SubscribeMessageSender.class);
        Channel channel = mock(Channel.class);
        SubscribeMessageEvent event = event();
        when(idempotent.isConsumed("evt-2")).thenReturn(false);
        when(ledger.isTerminal("POINTS_CHANGE:1")).thenReturn(false);
        when(storeNameResolver.resolve(101L)).thenReturn(null);

        new MarketingSubscribeConsumer(idempotent, ledger, configured(), storeNameResolver,
                userMapper, sender, new MarketingSubscribeMessageFactory())
                .onMessage(message(event, "evt-2", 2L), channel);

        verify(ledger).markSkipped("POINTS_CHANGE:1", -1, "门店不存在或已失效");
        verify(sender, never()).send(anyString(), any(), any(), any());
    }

    @Test
    void retriesTransientWechatFailure() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        StoreNameResolver storeNameResolver = mock(StoreNameResolver.class);
        AppUserMapper userMapper = mock(AppUserMapper.class);
        SubscribeMessageSender sender = mock(SubscribeMessageSender.class);
        MqProducer producer = mock(MqProducer.class);
        Channel channel = mock(Channel.class);
        SubscribeMessageEvent event = event();
        AppUser user = new AppUser();
        user.setOpenId("openid-9");
        when(idempotent.isConsumed("evt-3")).thenReturn(false);
        when(ledger.isTerminal("POINTS_CHANGE:1")).thenReturn(false);
        when(storeNameResolver.resolve(101L)).thenReturn("系统门店A");
        when(userMapper.selectById(9L)).thenReturn(user);
        when(sender.send(eq("openid-9"), any(), any(), any()))
                .thenReturn(new SubscribeMessageSender.SendResult(45009, "rate limit"));
        doNothing().when(producer).sendRawToExchangeWithId(
                anyString(), anyString(), any(Message.class), anyString(), anyString());

        MarketingSubscribeConsumer consumer = new MarketingSubscribeConsumer(
                idempotent, ledger, configured(), storeNameResolver, userMapper, sender,
                new MarketingSubscribeMessageFactory());
        consumer.setMqProducer(producer);
        consumer.onMessage(message(event, "evt-3", 3L), channel);

        verify(ledger).markFailed("POINTS_CHANGE:1", 45009, "rate limit");
        verify(producer).sendRawToExchangeWithId(
                eq(MqConstants.RETRY_EXCHANGE), eq(MqConstants.SUBSCRIBE_MARKETING_ROUTING_KEY),
                any(Message.class), eq("evt-3"), eq("POINTS_CHANGE:1"));
        verify(idempotent, never()).markConsumed("evt-3");
    }

    private SubscribeMessageEvent event() {
        return new SubscribeMessageEvent(
                SubscribeMessageEvent.CURRENT_VERSION,
                "pointsChange",
                9L,
                101L,
                "POINTS_CHANGE:1",
                null,
                Map.of("amount", 10L, "balanceAfter", 110L, "reason", "每日签到",
                        "time", LocalDateTime.of(2026, 10, 22, 10, 0)),
                "2026-10-22T10:00:00Z");
    }

    private Message message(SubscribeMessageEvent event, String messageId, long deliveryTag) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey(MqConstants.SUBSCRIBE_MARKETING_ROUTING_KEY);
        properties.setMessageId(messageId);
        properties.setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
        properties.setHeader(MqConstants.HEADER_BIZ_KEY, event.messageKey());
        return new Message(objectMapper.writeValueAsBytes(event), properties);
    }

    private WxMarketingSubscribeProperties configured() {
        WxMarketingSubscribeProperties properties = new WxMarketingSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret");
        properties.getSubscribe().setEnabled(true);
        properties.getSubscribe().getPointsChange().setTemplateId("template-points");
        properties.getSubscribe().getPointsChange().setPage("pages/points-detail/points-detail");
        return properties;
    }
}
