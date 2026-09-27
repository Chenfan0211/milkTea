package com.wuling.marketing.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.MqProducer;
import com.wuling.marketing.service.CouponService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponEventConsumerTest {

    private MqIdempotent idempotent;
    private CouponService couponService;
    private Channel channel;
    private CouponEventConsumer consumer;

    @BeforeEach
    void setUp() {
        idempotent = mock(MqIdempotent.class);
        couponService = mock(CouponService.class);
        channel = mock(Channel.class);
        consumer = new CouponEventConsumer(idempotent, couponService);
    }

    @Test
    @DisplayName("重复 messageId 的优惠券事件只消费一次")
    void duplicateMessageIdConsumesOnce() throws Exception {
        when(idempotent.tryAcquire("evt-coupon-1")).thenReturn(true, false);
        String body = """
                {"eventId":"evt-coupon-1","action":"CONSUME","userId":9,"userCouponId":10,"orderNo":"ORDER-1"}
                """;

        consumer.onCouponEvent(message(body), channel);
        consumer.onCouponEvent(message(body), channel);

        verify(couponService, times(1)).consume(9L, 10L, "ORDER-1");
        verify(idempotent, times(2)).tryAcquire("evt-coupon-1");
        verify(channel, times(2)).basicAck(anyLong(), eq(false));
    }

    @Test
    @DisplayName("CONSUME、RELEASE、RESTORE_REFUND 路由到既有幂等状态流转")
    void routesSupportedActions() throws Exception {
        when(idempotent.tryAcquire("evt-consume")).thenReturn(true);
        when(idempotent.tryAcquire("evt-release")).thenReturn(true);
        when(idempotent.tryAcquire("evt-restore")).thenReturn(true);

        consumer.onCouponEvent(message("""
                {"eventId":"evt-consume","action":"CONSUME","userId":9,"userCouponId":10,"orderNo":"ORDER-1"}
                """), channel);
        consumer.onCouponEvent(message("""
                {"eventId":"evt-release","action":"RELEASE","userId":9,"userCouponId":11,"orderNo":"ORDER-2","reason":"取消订单"}
                """), channel);
        consumer.onCouponEvent(message("""
                {"eventId":"evt-restore","action":"RESTORE_REFUND","userId":9,"userCouponId":11,"orderNo":"ORDER-2"}
                """), channel);

        verify(couponService).consume(9L, 10L, "ORDER-1");
        verify(couponService).release("ORDER-2", "取消订单");
        verify(couponService).restoreAfterRefund(9L, 11L, "ORDER-2");
        verify(channel, times(3)).basicAck(anyLong(), eq(false));
    }

    @Test
    @DisplayName("缺少 Broker messageId 时按 action、订单和券生成稳定幂等键")
    void derivesStableMessageIdWithoutBrokerHeader() throws Exception {
        String messageId = "COUPON:CONSUME:ORDER-3:12";
        when(idempotent.tryAcquire(messageId)).thenReturn(true, false);
        String body = """
                {"action":"CONSUME","userId":9,"userCouponId":12,"orderNo":"ORDER-3"}
                """;

        Message first = message(body);
        Message duplicate = message(body);
        consumer.onCouponEvent(first, channel);
        consumer.onCouponEvent(duplicate, channel);

        assertEquals(messageId, first.getMessageProperties().getMessageId());
        assertEquals(messageId, first.getMessageProperties().getHeaders().get(MqConstants.HEADER_MESSAGE_ID));
        verify(couponService, times(1)).consume(9L, 12L, "ORDER-3");
        verify(channel, times(2)).basicAck(anyLong(), eq(false));
    }

    @Test
    @DisplayName("业务失败释放幂等占位并投递延迟重试")
    void businessFailurePublishesRetry() throws Exception {
        MqProducer mqProducer = mock(MqProducer.class);
        consumer.setMqProducer(mqProducer);
        when(idempotent.tryAcquire("evt-retry")).thenReturn(true);
        when(couponService.consume(9L, 14L, "ORDER-5"))
                .thenThrow(new IllegalStateException("db down"));
        Message message = message("""
                {"eventId":"evt-retry","action":"CONSUME","userId":9,"userCouponId":14,"orderNo":"ORDER-5"}
                """);
        message.getMessageProperties().setReceivedRoutingKey(MqConstants.COUPON_EVENT_ROUTING_KEY);

        consumer.onCouponEvent(message, channel);

        verify(idempotent).release("evt-retry");
        verify(mqProducer).sendRawToExchangeWithId(
                eq(MqConstants.RETRY_EXCHANGE),
                eq(MqConstants.COUPON_EVENT_ROUTING_KEY),
                any(Message.class),
                eq("evt-retry"),
                eq("ORDER-5"));
        verify(channel).basicAck(anyLong(), eq(false));
    }

    @Test
    @DisplayName("兼容 outbox payload 包装格式")
    void acceptsOutboxPayloadWrapper() throws Exception {
        when(idempotent.tryAcquire("evt-wrapped")).thenReturn(true);

        consumer.onCouponEvent(message("""
                {"eventId":"evt-wrapped","payload":{"action":"RESTORE_REFUND","userId":9,"userCouponId":13,"orderNo":"ORDER-4"}}
                """), channel);

        verify(couponService).restoreAfterRefund(9L, 13L, "ORDER-4");
        verify(channel).basicAck(anyLong(), eq(false));
    }

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(101L);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
