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
 * <p><b>为什么不把「是否已结算」的校验也放到这里</b>：
 * trade 在受理退款前必须<b>同步</b>判断订单是否已进入可结算/已结算状态，
 * 否则会出现「钱已进可用余额却仍被退款」的资金穿透。
 * 该判断无法用异步事件替代，因此 trade 侧保留对 settlement_record 的同步只读查询。
 * 本消费者只负责「冲正」这一动作。
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
                ledgerService.reverseForOrder(event.getOrderNo());
                log.info("退款冲正完成 orderNo={}", event.getOrderNo());
            } catch (IllegalStateException e) {
                // 订单已进入可结算/已结算：冲正被拒绝。
                // 这属于业务约束触发，重试也不会成功，记为告警由人工/对账介入。
                log.error("退款冲正被拒绝（订单可能已结算），需人工核对 orderNo={} msg={}",
                        event.getOrderNo(), e.getMessage());
            }
        });
    }
}
