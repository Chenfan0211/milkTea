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
 * <p>统一处理幂等、延迟重试、死信和 ACK。默认使用投递前占位；对不能静默丢失的
 * 订阅消息链路使用 {@link #consumeAfterSuccess}，仅当业务成功后才写入幂等标记。</p>
 */
public abstract class AbstractMqConsumer {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Value("${app.mq.max-retry:3}")
    protected int maxRetry = 3;

    @Value("${app.mq.retry-delay-ms:10000}")
    protected long retryDelayMs = 10_000L;

    @Value("${app.mq.retry-max-delay-ms:600000}")
    protected long maxRetryDelayMs = 600_000L;

    protected final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final MqIdempotent idempotent;
    private MqProducer mqProducer;

    protected AbstractMqConsumer(MqIdempotent idempotent) {
        this.idempotent = idempotent;
    }

    @Autowired(required = false)
    public void setMqProducer(MqProducer mqProducer) {
        this.mqProducer = mqProducer;
    }

    /** 默认消费入口：投递前占位，失败释放占位。 */
    protected void consume(Message message, Channel channel, MessageHandler handler) {
        MessageProperties props = message.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        String messageId = messageId(props);
        String bizKey = bizKey(props);

        try {
            if (!idempotent.tryAcquire(messageId)) {
                log.info("MQ 重复消息已跳过 messageId={} bizKey={}", messageId, bizKey);
                channel.basicAck(deliveryTag, false);
                return;
            }
            handler.handle(message);
            channel.basicAck(deliveryTag, false);
            log.info("MQ 消费成功 messageId={} bizKey={}", messageId, bizKey);
        } catch (Exception e) {
            idempotent.release(messageId);
            handleFailure(message, channel, deliveryTag, messageId, bizKey, e);
        }
    }

    /**
     * 成功后再幂等模式：先执行业务，成功后标记并 ACK。
     *
     * <p>进程在业务执行前崩溃不会留下“已消费”标记；业务异常会进入既有延迟重试，
     * 适合订阅消息等不能因 Redis 预占位而丢失的场景。</p>
     */
    protected void consumeAfterSuccess(Message message, Channel channel, MessageHandler handler) {
        MessageProperties props = message.getMessageProperties();
        long deliveryTag = props.getDeliveryTag();
        String messageId = messageId(props);
        String bizKey = bizKey(props);

        try {
            if (idempotent.isConsumed(messageId)) {
                log.info("MQ 重复消息已跳过 messageId={} bizKey={}", messageId, bizKey);
                channel.basicAck(deliveryTag, false);
                return;
            }
            handler.handle(message);
            idempotent.markConsumed(messageId);
            channel.basicAck(deliveryTag, false);
            log.info("MQ 消费成功 messageId={} bizKey={}", messageId, bizKey);
        } catch (Exception e) {
            handleFailure(message, channel, deliveryTag, messageId, bizKey, e);
        }
    }

    private void handleFailure(Message message,
                               Channel channel,
                               long deliveryTag,
                               String messageId,
                               String bizKey,
                               Exception error) {
        int retry = getRetryCount(message.getMessageProperties());
        if (retry < maxRetry) {
            publishRetry(message, channel, deliveryTag, messageId, bizKey, retry, error);
        } else {
            log.error("MQ 重试超限，转入死信队列 messageId={} bizKey={} retry={}/{}",
                    messageId, bizKey, retry, maxRetry, error);
            nack(channel, deliveryTag, false, messageId);
        }
    }

    private String messageId(MessageProperties props) {
        String messageId = (String) props.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);
        return messageId == null ? props.getMessageId() : messageId;
    }

    private String bizKey(MessageProperties props) {
        return (String) props.getHeaders().get(MqConstants.HEADER_BIZ_KEY);
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
