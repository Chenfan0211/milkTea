package com.wuling.finance.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.OrderRefundedEvent;
import com.wuling.finance.service.LedgerService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 退款冲正消费者（第 6 期新增）。
 *
 * <p>幂等分三层：Broker messageId、按订单派生的稳定 messageId，以及
 * {@code settlement_record} 行锁 + 条件状态更新。任何一层都不能替代数据库最终防线。
 */
@Component
public class OrderRefundedReverseConsumer extends AbstractMqConsumer {

    private final LedgerService ledgerService;

    public OrderRefundedReverseConsumer(MqIdempotent idempotent, LedgerService ledgerService) {
        super(idempotent);
        this.ledgerService = ledgerService;
    }

    @RabbitListener(queues = MqConstants.FINANCE_REVERSE_QUEUE)
    public void onOrderRefunded(Message message, Channel channel) {
        FinanceMqEventReader.ensureStableMessageId(message, objectMapper, "REVERSE");
        consume(message, channel, msg -> {
            OrderRefundedEvent event = FinanceMqEventReader.read(objectMapper, msg, OrderRefundedEvent.class);
            if (event.getOrderNo() == null) {
                throw new IllegalArgumentException("退款冲正事件缺少 orderNo");
            }
            ledgerService.reverseForOrder(event.getOrderNo(), event.getRefundNo());
            log.info("退款冲正完成 orderNo={} refundNo={}", event.getOrderNo(), event.getRefundNo());
        });
    }
}