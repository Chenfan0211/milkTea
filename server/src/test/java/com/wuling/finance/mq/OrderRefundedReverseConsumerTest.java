package com.wuling.finance.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.finance.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRefundedReverseConsumerTest {

    private MqIdempotent idempotent;
    private LedgerService ledgerService;
    private Channel channel;
    private OrderRefundedReverseConsumer consumer;

    @BeforeEach
    void setUp() {
        idempotent = mock(MqIdempotent.class);
        ledgerService = mock(LedgerService.class);
        channel = mock(Channel.class);
        consumer = new OrderRefundedReverseConsumer(idempotent, ledgerService);
    }

    @Test
    void derivesStableOrderMessageIdAndSkipsDuplicateDelivery() throws Exception {
        when(idempotent.tryAcquire("ORDER-1:REVERSE")).thenReturn(true, false);

        Message first = message("{\"orderNo\":\"ORDER-1\",\"refundNo\":\"REFUND-1\"}");
        Message duplicate = message("{\"orderNo\":\"ORDER-1\",\"refundNo\":\"REFUND-1\"}");
        consumer.onOrderRefunded(first, channel);
        consumer.onOrderRefunded(duplicate, channel);

        assertEquals("ORDER-1:REVERSE", first.getMessageProperties().getMessageId());
        assertEquals("ORDER-1:REVERSE", first.getMessageProperties().getHeaders()
                .get(MqConstants.HEADER_MESSAGE_ID));
        assertEquals("ORDER-1:REVERSE", duplicate.getMessageProperties().getMessageId());
        verify(ledgerService, times(1)).reverseForOrder("ORDER-1", "REFUND-1");
        verify(idempotent, times(2)).tryAcquire("ORDER-1:REVERSE");
        verify(channel, times(2)).basicAck(anyLong(), eq(false));
    }

    @Test
    void acceptsEventOutboxPayloadWrapper() throws Exception {
        when(idempotent.tryAcquire("evt-100")).thenReturn(true);

        Message message = message("""
                {"eventId":"evt-100","payload":{"orderNo":"ORDER-2","refundNo":"REFUND-2"}}
                """);
        consumer.onOrderRefunded(message, channel);

        assertEquals("evt-100", message.getMessageProperties().getMessageId());
        verify(ledgerService).reverseForOrder("ORDER-2", "REFUND-2");
        verify(channel).basicAck(anyLong(), eq(false));
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(101L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
