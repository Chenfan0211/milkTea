package com.wuling.marketing.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.OrderPaidEvent;
import com.wuling.marketing.service.GrowthService;
import com.wuling.marketing.service.PointsService;
import com.wuling.marketing.service.ReferralRewardService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 订单支付成功消费者：触发会员成长值累加 + 邀请好友首单奖励 + 消费发放时光币。
 *
 * <p>三个职责顺序执行、幂等相互独立：
 * <ul>
 *   <li>成长值：按 growth_record.order_no 唯一键幂等（只算点单消费）；</li>
 *   <li>邀请奖励：按 referral_record.invitee_user_id 唯一键幂等。</li>
 *   <li>时光币：按 points_record(order_no, source) 唯一键幂等（每消费 1 元 +1 币）。</li>
 * </ul>
 * 任一失败不影响支付；本消费者依靠 MQ 重试保证最终一致。
 */
@Component
public class ReferralRewardConsumer extends AbstractMqConsumer {

    private final ReferralRewardService referralRewardService;
    private final GrowthService growthService;
    private final PointsService pointsService;

    public ReferralRewardConsumer(MqIdempotent idempotent,
                                  ReferralRewardService referralRewardService,
                                  GrowthService growthService,
                                  PointsService pointsService) {
        super(idempotent);
        this.referralRewardService = referralRewardService;
        this.growthService = growthService;
        this.pointsService = pointsService;
    }

    @RabbitListener(queues = MqConstants.PAYMENT_SUCCESS_QUEUE)
    public void onOrderPaid(Message message, Channel channel) {
        consume(message, channel, msg -> {
            OrderPaidEvent event = objectMapper.readValue(msg.getBody(), OrderPaidEvent.class);
            if (event.getUserId() == null) {
                log.warn("支付成功消息缺少 userId，已忽略 orderNo={}", event.getOrderNo());
                return;
            }
            // 1) 累加会员成长值（只算点单消费；order_no 唯一键幂等）
            try {
                growthService.addSpend(event.getUserId(),
                        event.getPaidAmount() == null ? 0L : event.getPaidAmount(),
                        event.getOrderNo());
            } catch (DuplicateKeyException e) {
                // 该订单已累加过：幂等跳过，不让重复消息打断后续邀请奖励
                log.info("成长值已累加过，跳过 orderNo={}", event.getOrderNo());
            }
            // 2) 触发邀请好友首单奖励
            referralRewardService.rewardFirstOrder(event.getUserId(), event.getOrderNo());
            // 3) 消费发放时光币（每消费 1 元 +1 币；余额支付/微信支付均计入）
            try {
                pointsService.awardForOrderPaid(event.getUserId(),
                        event.getPaidAmount(), event.getOrderNo());
            } catch (DuplicateKeyException e) {
                // 该订单已发放过：幂等跳过
                log.info("时光币已发放过，跳过 orderNo={}", event.getOrderNo());
            }
        });
    }
}
