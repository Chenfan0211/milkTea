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
 * <p>背景：trade 退款成功后需冲正 finance 的待结算台账。
 * 为解除 trade -> finance 的编译期依赖，改为事件驱动。
 *
 * <p>本消费者负责按结算记录冲正资金；若任一账户余额不足，
 * LedgerService 会整批回滚并独立写入对账异常。
 *
 * <p>幂等：{@code reverseForOrder} 会把记录置为 CANCELED，
 * 重复消费时查不到待结算记录，自然无副作用；
 * 另有 {@code MqIdempotent} 按 messageId 去重。
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
        consume(message, channel, msg -> {
            OrderRefundedEvent event = objectMapper.readValue(msg.getBody(), OrderRefundedEvent.class);
            if (event.getOrderNo() == null) {
                log.warn("退款冲正事件缺少 orderNo，已忽略");
                return;
            }
            try {
                ledgerService.reverseForOrder(event.getOrderNo(), event.getRefundNo());
                log.info("退款冲正完成 orderNo={} refundNo={}", event.getOrderNo(), event.getRefundNo());
            } catch (IllegalStateException e) {
                log.error("退款冲正遇到未知结算状态，需人工核对 orderNo={} refundNo={} msg={}",
                        event.getOrderNo(), event.getRefundNo(), e.getMessage());
            }
        });
    }
}
