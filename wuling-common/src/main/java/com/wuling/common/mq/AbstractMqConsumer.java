package com.wuling.common.mq;

import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;

/**
 * 消息消费基类。
 *
 * 统一处理三件事，业务方只需关注 {@link #handle}：
 * 1. 幂等：同一 messageId 只处理一次
 * 2. 重试：失败后重新入队，最多 N 次
 * 3. 死信：超过重试次数后 nack(requeue=false)，由 DLX 转入死信队列
 *
 * 用法：
 * <pre>
 *   &#64;RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE)
 *   public void onOrderTimeout(Message message, Channel channel) {
 *       consume(message, channel, this::handleOrderTimeout);
 *   }
 * </pre>
 */
public abstract class AbstractMqConsumer {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Value("${app.mq.max-retry:3}")
    protected int maxRetry;

    /**
      消息体反序列化用。子类消费 JSON 消息时直接用这个，
      避免每个消费者各建一个 ObjectMapper。
    */
    protected final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private final MqIdempotent idempotent;

    protected AbstractMqConsumer(MqIdempotent idempotent) {
        this.idempotent = idempotent;
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
        String bizKey = (String) props.getHeaders().get("biz-key");

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
            // 业务失败：释放幂等占位，允许重试
            idempotent.release(messageId);

            int retry = getRetryCount(props);
            if (retry < maxRetry) {
                log.warn("MQ 消费失败，将重试({}/{}) messageId={} bizKey={} err={}",
                        retry + 1, maxRetry, messageId, bizKey, e.getMessage());
                try {
                    // requeue=true：重新入队（配合 prefetch 不会造成无限循环压力）
                    channel.basicNack(deliveryTag, false, true);
                } catch (IOException io) {
                    log.error("MQ nack 失败 messageId={}", messageId, io);
                }
            } else {
                log.error("MQ 重试超限，转入死信队列 messageId={} bizKey={}", messageId, bizKey, e);
                try {
                    // requeue=false：交由 DLX 转入死信队列
                    channel.basicNack(deliveryTag, false, false);
                } catch (IOException io) {
                    log.error("MQ nack(dead-letter) 失败 messageId={}", messageId, io);
                }
            }
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
