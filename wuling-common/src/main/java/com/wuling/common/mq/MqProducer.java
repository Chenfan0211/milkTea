package com.wuling.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 消息发送封装。
 *
 * <p>发送方法使用 publisher confirm；只有 Broker ACK 且消息没有被
 * mandatory return 后，方法才正常返回。</p>
 */
@Component
@ConditionalOnClass(RabbitTemplate.class)
public class MqProducer {

    private static final Logger log = LoggerFactory.getLogger(MqProducer.class);
    private static final long DEFAULT_CONFIRM_TIMEOUT_MS = 10_000L;

    private final RabbitTemplate rabbitTemplate;
    private final long confirmTimeoutMs;

    @Autowired
    public MqProducer(RabbitTemplate rabbitTemplate) {
        this(rabbitTemplate, DEFAULT_CONFIRM_TIMEOUT_MS);
    }

    public MqProducer(RabbitTemplate rabbitTemplate, long confirmTimeoutMs) {
        if (rabbitTemplate == null) {
            throw new IllegalArgumentException("rabbitTemplate must not be null");
        }
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeoutMs = confirmTimeoutMs <= 0 ? DEFAULT_CONFIRM_TIMEOUT_MS : confirmTimeoutMs;
    }

    /** 发送业务消息到业务交换机。 */
    public void send(String routingKey, Object payload) {
        send(routingKey, payload, null);
    }

    /** 发送消息，可指定业务键（如 orderNo）便于日志追踪。 */
    public void send(String routingKey, Object payload, String bizKey) {
        sendWithId(routingKey, payload, UUID.randomUUID().toString(), bizKey);
    }

    /**
     * 发送业务消息并等待 Broker confirm。
     *
     * @param routingKey 业务路由键
     * @param payload    消息体
     * @param messageId  消息唯一 ID
     * @param bizKey     业务追踪键
     */
    public void sendWithId(String routingKey, Object payload, String messageId, String bizKey) {
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.convertAndSend(
                MqConstants.BUSINESS_EXCHANGE,
                routingKey,
                payload,
                message -> prepareMessage(message, routingKey, messageId, bizKey),
                correlationData);
        waitForConfirm(correlationData, MqConstants.BUSINESS_EXCHANGE, routingKey, messageId);
        log.info("MQ 发送 exchange={} routingKey={} messageId={} bizKey={}",
                MqConstants.BUSINESS_EXCHANGE, routingKey, messageId, bizKey);
    }

    /**
     * 发送原始 Message 到指定交换机，并等待 Broker confirm。
     *
     * <p>主要用于消费失败后的延迟重试。重试 message 已经由调用方构造，
     * 本方法只补齐 messageId/biz-key/持久化属性，不改变消息体。</p>
     */
    public void sendRawToExchangeWithId(String exchange,
                                        String routingKey,
                                        Message message,
                                        String messageId,
                                        String bizKey) {
        prepareMessage(message, routingKey, messageId, bizKey);
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.send(exchange, routingKey, message, correlationData);
        waitForConfirm(correlationData, exchange, routingKey, messageId);
        log.info("MQ 发送原始消息 exchange={} routingKey={} messageId={} bizKey={}",
                exchange, routingKey, messageId, bizKey);
    }

    /**
     * 发送延迟消息（订单超时场景）。
     * 消息先进入延迟交换机，TTL 到期后自动转入业务队列。
     */
    public void sendDelay(String routingKey, Object payload, String bizKey) {
        String messageId = UUID.randomUUID().toString();
        CorrelationData correlationData = new CorrelationData(messageId);
        rabbitTemplate.convertAndSend(
                MqConstants.DELAY_EXCHANGE,
                routingKey,
                payload,
                message -> prepareMessage(message, routingKey, messageId, bizKey),
                correlationData);
        waitForConfirm(correlationData, MqConstants.DELAY_EXCHANGE, routingKey, messageId);
        log.info("MQ 发送延迟消息 routingKey={} messageId={} bizKey={}",
                routingKey, messageId, bizKey);
    }

    private Message prepareMessage(Message message, String routingKey, String messageId, String bizKey) {
        message.getMessageProperties().setMessageId(messageId);
        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        message.getMessageProperties().setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
        if (bizKey != null) {
            message.getMessageProperties().setHeader(MqConstants.HEADER_BIZ_KEY, bizKey);
        }
        return message;
    }

    private void waitForConfirm(CorrelationData correlationData,
                                String exchange,
                                String routingKey,
                                String messageId) {
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new AmqpException("MQ mandatory return exchange=" + exchange
                        + " routingKey=" + routingKey
                        + " replyCode=" + returned.getReplyCode()
                        + " replyText=" + returned.getReplyText()
                        + " messageId=" + messageId);
            }
            if (!confirm.isAck()) {
                throw new AmqpException("MQ broker nack exchange=" + exchange
                        + " routingKey=" + routingKey
                        + " reason=" + confirm.getReason()
                        + " messageId=" + messageId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("MQ publish interrupted exchange=" + exchange
                    + " routingKey=" + routingKey + " messageId=" + messageId, e);
        } catch (ExecutionException e) {
            throw new AmqpException("MQ publish failed exchange=" + exchange
                    + " routingKey=" + routingKey + " messageId=" + messageId, e.getCause());
        } catch (TimeoutException e) {
            throw new AmqpException("MQ publisher confirm timeout exchange=" + exchange
                    + " routingKey=" + routingKey + " messageId=" + messageId, e);
        }
    }
}