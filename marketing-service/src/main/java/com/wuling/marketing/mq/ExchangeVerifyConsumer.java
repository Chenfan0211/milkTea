package com.wuling.marketing.mq;

import com.fasterxml.jackson.databind.JsonNode;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.marketing.service.PointsService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 兑换核销消费者（第 5 期新增）。
 *
 * <p>背景：trade 服务核销兑换码时，兑换单（ExchangeOrder）归属 marketing 服务。
 * 为避免 trade 直接依赖 marketing（跨服务强耦合、且 trade 已迁出单体），
 * 改为事件驱动：trade 发布「兑换核销」事件，本消费者负责更新兑换单状态。
 *
 * <p>一致性模型：<b>最终一致</b>
 * <ul>
 *   <li>trade 侧核销记录先落库（门店已据此放行），不阻塞业务；</li>
 *   <li>本消费者异步把兑换单置为 VERIFIED；</li>
 *   <li>失败自动重试（最多 {@code app.mq.max-retry} 次），超限转入死信队列；</li>
 *   <li>幂等由 {@link AbstractMqConsumer} 基于 messageId 保证，重复投递不会重复核销。</li>
 * </ul>
 *
 * <p>注意：{@code PointsService.verifyExchange} 自身对「已核销」状态会抛业务异常，
 * 这里需把该情形视为成功（幂等），否则会把已完成的消息反复重试。
 */
@Component
public class ExchangeVerifyConsumer extends AbstractMqConsumer {

    private final PointsService pointsService;


    public ExchangeVerifyConsumer(MqIdempotent idempotent,
                                  PointsService pointsService) {
        super(idempotent);
        this.pointsService = pointsService;

    }

    @RabbitListener(queues = MqConstants.EXCHANGE_VERIFY_QUEUE)
    public void onExchangeVerify(Message message, Channel channel) {
        consume(message, channel, msg -> {
            JsonNode node = objectMapper.readTree(msg.getBody());
            String pickupCode = node.path("pickupCode").asText(null);
            if (pickupCode == null || pickupCode.isBlank()) {
                // 消息体不合法：直接丢弃（重试也不会成功）
                log.warn("兑换核销消息缺少 pickupCode，已忽略");
                return;
            }
            try {
                pointsService.verifyExchange(pickupCode);
                log.info("兑换单核销成功 pickupCode={}", pickupCode);
            } catch (com.wuling.common.exception.BusinessException e) {
                // 已核销等业务态：视为幂等成功，不再重试
                log.info("兑换单核销跳过（业务态） pickupCode={} msg={}", pickupCode, e.getMessage());
            }
        });
    }
}
