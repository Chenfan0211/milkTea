package com.wuling.common.mq;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbstractMqConsumerRetryTest {

    @Test
    void shouldPublishRetryMessageWithIncrementedHeader() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        MqProducer producer = mock(MqProducer.class);
        Channel channel = mock(Channel.class);
        when(idempotent.tryAcquire("msg-1")).thenReturn(true);
        doNothing().when(producer).sendRawToExchangeWithId(
                anyString(), anyString(), any(Message.class), anyString(), anyString());

        TestConsumer consumer = new TestConsumer(idempotent, 3);
        consumer.setProducer(producer);
        Message message = message("msg-1", 0, "O-1", 7L);

        consumer.consumeForTest(message, channel, ignored -> {
            throw new IllegalStateException("business failure");
        });

        org.mockito.ArgumentCaptor<Message> retryMessage = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(producer).sendRawToExchangeWithId(
                eq(MqConstants.RETRY_EXCHANGE), eq("wuling.order.timeout"),
                retryMessage.capture(), eq("msg-1"), eq("O-1"));
        assertEquals(1, retryMessage.getValue().getMessageProperties()
                .getHeaders().get(MqConstants.HEADER_RETRY_COUNT));
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(7L, false, false);
        verify(idempotent).release("msg-1");
    }

    @Test
    void shouldDeadLetterWhenRetryCountReachedMax() throws Exception {
        MqIdempotent idempotent = mock(MqIdempotent.class);
        MqProducer producer = mock(MqProducer.class);
        Channel channel = mock(Channel.class);
        when(idempotent.tryAcquire("msg-2")).thenReturn(true);

        TestConsumer consumer = new TestConsumer(idempotent, 3);
        consumer.setProducer(producer);
        Message message = message("msg-2", 3, "O-2", 8L);

        consumer.consumeForTest(message, channel, ignored -> {
            throw new IllegalStateException("still failing");
        });

        verify(channel).basicNack(8L, false, false);
        verify(channel, never()).basicAck(8L, false);
        verify(producer, never()).sendRawToExchangeWithId(
                anyString(), anyString(), any(Message.class), anyString(), anyString());
    }

    private Message message(String messageId, int retryCount, String bizKey, long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        properties.setReceivedRoutingKey(MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
        properties.setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
        properties.setHeader(MqConstants.HEADER_RETRY_COUNT, retryCount);
        properties.setHeader("biz-key", bizKey);
        return new Message("order-no".getBytes(StandardCharsets.UTF_8), properties);
    }

    private static final class TestConsumer extends AbstractMqConsumer {

        TestConsumer(MqIdempotent idempotent, int maxRetry) {
            super(idempotent);
            this.maxRetry = maxRetry;
        }

        void setProducer(MqProducer producer) {
            setMqProducer(producer);
        }

        void consumeForTest(Message message, Channel channel, MessageHandler handler) {
            consume(message, channel, handler);
        }
    }
}