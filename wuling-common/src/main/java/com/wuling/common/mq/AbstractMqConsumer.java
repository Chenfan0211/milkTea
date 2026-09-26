package com.wuling.common.mq;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.HashMap;

/**
 * 消息消费基类。
 *
 * <p>统一处理幂等、延迟重试、死信和 ACK。业务方只需关注
 * {@link #handle}。失败消息不原样 requeue，而是发布到重试交换机，
 * 在重试队列等待 TTL 后回到原业务队列；超过最大次数后 nack 到 DLQ。</p>
 */
public abstract class AbstractMqConsumer {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Value("${app.mq.max-retry:3}")
    protected int maxRetry = 3;

    @Value("${app.mq.retry-delay-ms:10000}")
    protected long retryDelayMs = 10_000L;

    @Value("${app.mq.retry-max-delay-ms:600000}")
    protected long maxRetryDelayMs = 600_000L;

    /**
      消息体反序列化用。子类消费 JSON 消息时直接用这个，
      避免每个消费者各建一个 ObjectMapper。
    */
    protected final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private final MqIdempotent idempotent;
    private MqProducer mqProducer;

    protected AbstractMqConsumer(MqIdempotent idempotent) {
        this.idempotent = idempotent;
    }

    @Autowired(required = false)
    public void setMqProducer(MqProducer mqProducer) {
        this.mqProducer = mqProducer;
    }

    /**
     * 消费入口：负责幂等 / 重试 / 死信 / ACK，业务异常不外抛。
     */
    protected void consume(Message message, Channel channel, MessageHandler handler) {
        MessageProperties props = message.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        String messageId = (String) props.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);
        if (messageId == null) {
            messageId = props.getMessageId();
        }
        String bizKey = (String) props.getHeaders().get(MqConstants.HEADER_BIZ_KEY);

        try {
            // 1) 幂等：已处理过则直接 ACK
            if (!idempotent.tryAcquire(messageId)) {
                log.info("MQ 重复消息已跳过 messageId={} bizKey={}", messageId, bizKey);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2) 业务处理
            handler.handle(message);
            channel.basicAck(deliveryTag, false);
            log.info("MQ 消费成功 messageId={} bizKey={}", messageId, bizKey);

        } catch (Exception e) {
            // 业务失败：释放幂等占位，允许重试消息再次处理。
            idempotent.release(messageId);

            int retry = getRetryCount(props);
            if (retry < maxRetry) {
                publishRetry(message, channel, deliveryTag, messageId, bizKey, retry, e);
            } else {
                log.error("MQ 重试超限，转入死信队列 messageId={} bizKey={} retry={}/{}",
                        messageId, bizKey, retry, maxRetry, e);
                nack(channel, deliveryTag, false, messageId);
            }
        }
    }

    private void publishRetry(Message message,
                              Channel channel,
                              long deliveryTag,
                              String messageId,
                              String bizKey,
                              int retry,
                              Exception businessError) {
        int nextRetry = retry + 1;
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        Message retryMessage = buildRetryMessage(message, nextRetry);
        try {
            if (mqProducer == null) {
                throw new IllegalStateException("MqProducer 未注入，无法发布延迟重试消息");
            }
            mqProducer.sendRawToExchangeWithId(
                    MqConstants.RETRY_EXCHANGE,
                    routingKey,
                    retryMessage,
                    messageId,
                    bizKey);
            channel.basicAck(deliveryTag, false);
            log.warn("MQ 消费失败，已投递延迟重试({}/{}) messageId={} bizKey={} routingKey={} err={}",
                    nextRetry, maxRetry, messageId, bizKey, routingKey, businessError.getMessage());
        } catch (Exception publishError) {
            log.error("MQ 延迟重试投递失败，原消息 requeue messageId={} bizKey={}", messageId, bizKey, publishError);
            nack(channel, deliveryTag, true, messageId);
        }
    }

    private Message buildRetryMessage(Message source, int nextRetry) {
        MessageProperties sourceProps = source.getMessageProperties();
        MessageProperties target = new MessageProperties();
        target.setHeaders(new HashMap<>(sourceProps.getHeaders()));
        target.setHeader(MqConstants.HEADER_RETRY_COUNT, nextRetry);
        target.setMessageId(sourceProps.getMessageId());
        target.setContentType(sourceProps.getContentType());
        target.setContentEncoding(sourceProps.getContentEncoding());
        target.setType(sourceProps.getType());
        target.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        target.setExpiration(String.valueOf(retryDelay(nextRetry)));
        return new Message(source.getBody(), target);
    }

    private long retryDelay(int nextRetry) {
        long base = Math.max(1L, retryDelayMs);
        long cap = Math.max(base, maxRetryDelayMs);
        int shift = Math.min(Math.max(nextRetry - 1, 0), 30);
        long delay = base;
        for (int i = 0; i < shift && delay < cap; i++) {
            if (delay > cap / 2) {
                return cap;
            }
            delay *= 2;
        }
        return Math.min(delay, cap);
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue, String messageId) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception e) {
            log.error("MQ nack 失败 messageId={} requeue={}", messageId, requeue, e);
        }
    }

    /** 业务处理接口 */
    @FunctionalInterface
    public interface MessageHandler {
        void handle(Message message) throws Exception;
    }

    private int getRetryCount(MessageProperties props) {
        Object value = props.getHeaders().get(MqConstants.HEADER_RETRY_COUNT);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}