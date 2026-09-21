package com.wuling.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * RabbitMQ 声明式配置。
 *
 * 基础设施组成：
 * 1. JSON 消息转换（默认 JDK 序列化不可读，改用 JSON 便于排查）
 * 2. 业务交换机 + 死信交换机
 * 3. 每个业务队列都绑定死信，消费失败超限后自动转 DLQ
 * 4. 消费端手动 ACK + 并发控制 + 失败重试
 * 5. 订单超时采用「死信 + TTL」实现延迟（无需插件）
 */
@Configuration
public class RabbitConfig {

    /** 订单超时时间（毫秒），默认 15 分钟 */
    @Value("${app.mq.order-timeout-ms:900000}")
    private long orderTimeoutMs;

    /** 消费端并发数 */
    @Value("${app.mq.consumer-concurrency:2}")
    private int consumerConcurrency;

    /** 单次预取数量 */
    @Value("${app.mq.prefetch-count:10}")
    private int prefetchCount;

    // ==================== 消息转换 ====================

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        // 消息无法路由时返回给发送方（配合 mandatory 使用）
        template.setMandatory(true);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(consumerConcurrency);
        factory.setMaxConcurrentConsumers(consumerConcurrency * 2);
        factory.setPrefetchCount(prefetchCount);
        // 手动 ACK：由消费者在业务成功后确认，失败则 nack 进死信
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    // ==================== 交换机 ====================

    @Bean
    public DirectExchange businessExchange() {
        return ExchangeBuilder.directExchange(MqConstants.BUSINESS_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(MqConstants.DLX_EXCHANGE).durable(true).build();
    }

    /** 延迟交换机：消息先投递到这里，TTL 到期后转入业务队列 */
    @Bean
    public DirectExchange delayExchange() {
        return ExchangeBuilder.directExchange(MqConstants.DELAY_EXCHANGE).durable(true).build();
    }

    // ==================== 订单超时（延迟实现） ====================

    /**
     * 订单超时延迟队列。
     * 消息只在此排队等待 TTL，不直接消费；到期后经 DLX 转入业务队列。
     */
    @Bean
    public Queue orderDelayQueue() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", orderTimeoutMs);
        args.put("x-dead-letter-exchange", MqConstants.BUSINESS_EXCHANGE);
        args.put("x-dead-letter-routing-key", MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_QUEUE + ".delay").withArguments(args).build();
    }

    @Bean
    public Binding orderDelayBinding() {
        return BindingBuilder.bind(orderDelayQueue()).to(delayExchange())
                .with(MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
    }

    /** 订单超时业务队列（TTL 到期后消息真正到达这里） */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_QUEUE)
                .deadLetterExchange(MqConstants.DLX_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.ORDER_TIMEOUT_DLQ)
                .build();
    }

    @Bean
    public Binding orderTimeoutBinding() {
        return BindingBuilder.bind(orderTimeoutQueue()).to(businessExchange())
                .with(MqConstants.ORDER_TIMEOUT_ROUTING_KEY);
    }

    // ==================== 支付成功 ====================

    @Bean
    public Queue paymentSuccessQueue() {
        return QueueBuilder.durable(MqConstants.PAYMENT_SUCCESS_QUEUE)
                .deadLetterExchange(MqConstants.DLX_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.PAYMENT_SUCCESS_DLQ)
                .build();
    }

    @Bean
    public Binding paymentSuccessBinding() {
        return BindingBuilder.bind(paymentSuccessQueue()).to(businessExchange())
                .with(MqConstants.PAYMENT_SUCCESS_ROUTING_KEY);
    }

    // ==================== 结算通知 ====================

    @Bean
    public Queue settlementNotifyQueue() {
        return QueueBuilder.durable(MqConstants.SETTLEMENT_NOTIFY_QUEUE)
                .deadLetterExchange(MqConstants.DLX_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.SETTLEMENT_NOTIFY_DLQ)
                .build();
    }

    @Bean
    public Binding settlementNotifyBinding() {
        return BindingBuilder.bind(settlementNotifyQueue()).to(businessExchange())
                .with(MqConstants.SETTLEMENT_NOTIFY_ROUTING_KEY);
    }

    // ==================== 死信队列 ====================

    @Bean
    public Queue orderTimeoutDlq() {
        return QueueBuilder.durable(MqConstants.ORDER_TIMEOUT_DLQ).build();
    }

    @Bean
    public Binding orderTimeoutDlqBinding() {
        return BindingBuilder.bind(orderTimeoutDlq()).to(dlxExchange())
                .with(MqConstants.ORDER_TIMEOUT_DLQ);
    }

    @Bean
    public Queue paymentSuccessDlq() {
        return QueueBuilder.durable(MqConstants.PAYMENT_SUCCESS_DLQ).build();
    }

    @Bean
    public Binding paymentSuccessDlqBinding() {
        return BindingBuilder.bind(paymentSuccessDlq()).to(dlxExchange())
                .with(MqConstants.PAYMENT_SUCCESS_DLQ);
    }

    @Bean
    public Queue settlementNotifyDlq() {
        return QueueBuilder.durable(MqConstants.SETTLEMENT_NOTIFY_DLQ).build();
    }

    @Bean
    public Binding settlementNotifyDlqBinding() {
        return BindingBuilder.bind(settlementNotifyDlq()).to(dlxExchange())
                .with(MqConstants.SETTLEMENT_NOTIFY_DLQ);
    }
}
