package com.wuling.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 消息发送封装。
 *
 * 统一处理：
 * - 自动生成消息 ID（供消费端幂等去重）
 * - 持久化投递（deliveryMode=PERSISTENT），Broker 重启不丢消息
 * - 统一日志，便于排查「消息是否发出」
 */
/** 需要 spring-boot-starter-amqp（wuling-common 中为 optional） */
@Component
@ConditionalOnClass(org.springframework.amqp.rabbit.core.RabbitTemplate.class)
public class MqProducer {

    private static final Logger log = LoggerFactory.getLogger(MqProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public MqProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送业务消息到业务交换机。
     *
     * @param routingKey 路由键（见 MqConstants）
     * @param payload    消息体（会被 JSON 序列化）
     */
    public void send(String routingKey, Object payload) {
        send(routingKey, payload, null);
    }

    /**
     * 发送消息，可指定业务键（如 orderNo）便于日志追踪。
     */
    public void send(String routingKey, Object payload, String bizKey) {
        String messageId = UUID.randomUUID().toString();
        sendWithId(routingKey, payload, messageId, bizKey);
    }

    public void sendWithId(String routingKey, Object payload, String messageId, String bizKey) {
        rabbitTemplate.convertAndSend(MqConstants.BUSINESS_EXCHANGE, routingKey, payload, message -> {
            message.getMessageProperties().setMessageId(messageId);
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
            if (bizKey != null) {
                message.getMessageProperties().setHeader("biz-key", bizKey);
            }
            return message;
        });
        log.info("MQ 发送 exchange={} routingKey={} messageId={} bizKey={}",
                MqConstants.BUSINESS_EXCHANGE, routingKey, messageId, bizKey);
    }

    /**
     * 发送延迟消息（订单超时场景）。
     * 消息先进入延迟队列等待 TTL，到期后自动转入对应业务队列。
     */
    public void sendDelay(String routingKey, Object payload, String bizKey) {
        String messageId = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend(MqConstants.DELAY_EXCHANGE, routingKey, payload, message -> {
            message.getMessageProperties().setMessageId(messageId);
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            message.getMessageProperties().setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
            if (bizKey != null) {
                message.getMessageProperties().setHeader("biz-key", bizKey);
            }
            return message;
        });
        log.info("MQ 发送延迟消息 routingKey={} messageId={} bizKey={}", routingKey, messageId, bizKey);
    }
}
