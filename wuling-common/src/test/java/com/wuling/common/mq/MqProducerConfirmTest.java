package com.wuling.common.mq;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessagePostProcessor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqProducerConfirmTest {

    @Test
    void sendWithIdReturnsOnlyAfterBrokerAck() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        Object payload = Map.of("orderNo", "O-1");
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(
                eq(MqConstants.BUSINESS_EXCHANGE), eq(MqConstants.ORDER_TIMEOUT_ROUTING_KEY),
                same(payload), any(MessagePostProcessor.class), any(CorrelationData.class));

        new MqProducer(template, 1_000)
                .sendWithId(MqConstants.ORDER_TIMEOUT_ROUTING_KEY, payload, "msg-1", "O-1");

        verify(template).convertAndSend(
                eq(MqConstants.BUSINESS_EXCHANGE), eq(MqConstants.ORDER_TIMEOUT_ROUTING_KEY),
                same(payload), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void sendWithIdThrowsWhenBrokerNacks() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        Object payload = Map.of("orderNo", "O-2");
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected"));
            return null;
        }).when(template).convertAndSend(
                any(String.class), any(String.class), same(payload),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        MqProducer producer = new MqProducer(template, 1_000);
        assertThrows(AmqpException.class, () -> producer.sendWithId(
                MqConstants.ORDER_TIMEOUT_ROUTING_KEY, payload, "msg-2", "O-2"));
    }

    @Test
    void sendWithIdThrowsWhenMandatoryMessageIsReturned() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        Object payload = Map.of("orderNo", "O-3");
        MessageProperties properties = new MessageProperties();
        Message returned = new Message(new byte[0], properties);
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(4);
            data.setReturned(new ReturnedMessage(
                    returned, 312, "NO_ROUTE", MqConstants.BUSINESS_EXCHANGE, "missing.route"));
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(
                any(String.class), any(String.class), same(payload),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        MqProducer producer = new MqProducer(template, 1_000);
        assertThrows(AmqpException.class, () -> producer.sendWithId(
                "missing.route", payload, "msg-3", "O-3"));
    }
}