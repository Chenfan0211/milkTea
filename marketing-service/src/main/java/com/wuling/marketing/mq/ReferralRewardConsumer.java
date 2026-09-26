package com.wuling.marketing.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.OrderPaidEvent;
import com.wuling.marketing.service.ReferralRewardService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单支付成功消费者：触发邀请好友首单奖励。
 *
 * <p>发奖失败不会影响支付；本消费者依靠 MQ 重试和发奖事务保证最终一致。
 */
@Component
public class ReferralRewardConsumer extends AbstractMqConsumer {

    private final ReferralRewardService referralRewardService;

    public ReferralRewardConsumer(MqIdempotent idempotent,
                                  ReferralRewardService referralRewardService) {
        super(idempotent);
        this.referralRewardService = referralRewardService;
    }

    @RabbitListener(queues = MqConstants.PAYMENT_SUCCESS_QUEUE)
    public void onOrderPaid(Message message, Channel channel) {
        consume(message, channel, msg -> {
            OrderPaidEvent event = objectMapper.readValue(msg.getBody(), OrderPaidEvent.class);
            if (event.getUserId() == null) {
                log.warn("支付成功消息缺少 userId，已忽略 orderNo={}", event.getOrderNo());
                return;
            }
            referralRewardService.rewardFirstOrder(event.getUserId(), event.getOrderNo());
        });
    }
}
