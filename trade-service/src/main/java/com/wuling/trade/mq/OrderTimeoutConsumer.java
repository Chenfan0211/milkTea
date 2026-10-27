package com.wuling.trade.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.trade.service.OrderService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 订单超时关闭消费者；商城库存释放由 OrderService 在同一关单事务内完成。 */
@Component
public class OrderTimeoutConsumer extends AbstractMqConsumer {

    private final OrderService orderService;

    public OrderTimeoutConsumer(MqIdempotent idempotent, OrderService orderService) {
        super(idempotent);
        this.orderService = orderService;
    }

    @RabbitListener(queues = MqConstants.ORDER_TIMEOUT_QUEUE)
    public void onOrderTimeout(Message message, Channel channel) {
        consume(message, channel, this::handle);
    }

    private void handle(Message message) {
        String orderNo = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"", "");
        if (orderNo.isEmpty()) {
            log.warn("订单超时消息体为空，跳过");
            return;
        }
        boolean closed = orderService.closeIfUnpaid(orderNo);
        if (closed) {
            log.info("订单已超时关闭 orderNo={}", orderNo);
        } else {
            log.info("订单已支付或已关闭，跳过 orderNo={}", orderNo);
        }
    }
}
