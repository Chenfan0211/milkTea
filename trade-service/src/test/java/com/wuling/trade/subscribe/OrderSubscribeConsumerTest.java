package com.wuling.trade.subscribe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.MqProducer;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import com.wuling.trade.port.UserQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSubscribeConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsAndMarksLedgerOnSuccess() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        UserQueryPort userQueryPort = mock(UserQueryPort.class);
        OrderSubscribeMessageClient client = mock(OrderSubscribeMessageClient.class);
        Channel channel = mock(Channel.class);
        SubscribeMessageEvent event = event();
        when(idempotent.isConsumed("evt-1")).thenReturn(false);
        when(ledger.isTerminal("ORDER_PAID:WX1")).thenReturn(false);
        when(userQueryPort.findOpenid(7L)).thenReturn("openid-7");
        when(client.sendEvent(event.values(), event.page(), "openid-7"))
                .thenReturn(new OrderSubscribeMessageClient.SendResult(0, "ok"));

        new OrderSubscribeConsumer(idempotent, ledger, userQueryPort, client)
                .onMessage(message(event, "evt-1", 1L), channel);

        verify(ledger).markSent("ORDER_PAID:WX1", 0, "ok");
        verify(idempotent).markConsumed("evt-1");
        verify(channel).basicAck(1L, false);
    }

    @Test
    void skipsWhenUserHasNoSubscriptionQuota() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        UserQueryPort userQueryPort = mock(UserQueryPort.class);
        OrderSubscribeMessageClient client = mock(OrderSubscribeMessageClient.class);
        Channel channel = mock(Channel.class);
        SubscribeMessageEvent event = event();
        when(idempotent.isConsumed("evt-2")).thenReturn(false);
        when(ledger.isTerminal("ORDER_PAID:WX1")).thenReturn(false);
        when(userQueryPort.findOpenid(7L)).thenReturn("openid-7");
        when(client.sendEvent(event.values(), event.page(), "openid-7"))
                .thenReturn(new OrderSubscribeMessageClient.SendResult(43101, "no quota"));

        new OrderSubscribeConsumer(idempotent, ledger, userQueryPort, client)
                .onMessage(message(event, "evt-2", 2L), channel);

        verify(ledger).markSkipped("ORDER_PAID:WX1", 43101, "no quota");
        verify(channel).basicAck(2L, false);
    }

    @Test
    void retriesTransientWechatFailure() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        UserQueryPort userQueryPort = mock(UserQueryPort.class);
        OrderSubscribeMessageClient client = mock(OrderSubscribeMessageClient.class);
        MqProducer producer = mock(MqProducer.class);
        Channel channel = mock(Channel.class);
        SubscribeMessageEvent event = event();
        when(idempotent.isConsumed("evt-3")).thenReturn(false);
        when(ledger.isTerminal("ORDER_PAID:WX1")).thenReturn(false);
        when(userQueryPort.findOpenid(7L)).thenReturn("openid-7");
        when(client.sendEvent(event.values(), event.page(), "openid-7"))
                .thenReturn(new OrderSubscribeMessageClient.SendResult(45009, "rate limit"));
        doNothing().when(producer).sendRawToExchangeWithId(
                anyString(), anyString(), any(Message.class), anyString(), anyString());

        OrderSubscribeConsumer consumer = new OrderSubscribeConsumer(idempotent, ledger, userQueryPort, client);
        consumer.setMqProducer(producer);
        consumer.onMessage(message(event, "evt-3", 3L), channel);

        verify(ledger).markFailed("ORDER_PAID:WX1", 45009, "rate limit");
        verify(producer).sendRawToExchangeWithId(
                eq(MqConstants.RETRY_EXCHANGE), eq(MqConstants.SUBSCRIBE_ORDER_ROUTING_KEY),
                any(Message.class), eq("evt-3"), eq("ORDER_PAID:WX1"));
        verify(idempotent, never()).markConsumed("evt-3");
    }

    private SubscribeMessageEvent event() {
        return new SubscribeMessageEvent(
                SubscribeMessageEvent.CURRENT_VERSION,
                "order",
                7L,
                null,
                "ORDER_PAID:WX1",
                "pages/order-detail/order-detail?orderNo=WX1",
                Map.of("pickupCode", "A001", "store", "系统门店A",
                        "summary", "珍珠奶茶", "orderTime", "2026年10月22日",
                        "remark", OrderSubscribeMessageClient.REMARK_PREPARATION_TEXT),
                "2026-10-22T10:00:00Z");
    }

    private Message message(SubscribeMessageEvent event, String messageId, long deliveryTag) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey(MqConstants.SUBSCRIBE_ORDER_ROUTING_KEY);
        properties.setMessageId(messageId);
        properties.setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
        properties.setHeader(MqConstants.HEADER_BIZ_KEY, event.messageKey());
        return new Message(objectMapper.writeValueAsBytes(event), properties);
    }
}
