package com.wuling.finance.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.OrderVerifiedEvent;
import com.wuling.finance.service.LedgerService;
import com.wuling.finance.service.SplitCalculator;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 核销分账消费者（第 6 期新增）。
 *
 * <p>背景：trade 核销订单后需执行五方分账，而分账逻辑归属 finance。
 * 为解除 trade -> finance 的编译期依赖，改为事件驱动：
 * trade 发布 {@link OrderVerifiedEvent}，本消费者执行 {@code executeSplit}。
 *
 * <p>一致性模型：<b>最终一致</b>
 * <ul>
 *   <li>trade 侧核销记录与订单状态已先落库，门店据此放行，不阻塞；</li>
 *   <li>本消费者异步执行分账，失败自动重试，超限转死信队列；</li>
 *   <li><b>双保险幂等</b>：{@code MqIdempotent} 按 messageId 去重，
 *       且 {@code LedgerService.executeSplit} 自身对同一 orderId 幂等
 *       （已存在快照则直接复用）。</li>
 * </ul>
 *
 * <p>遗漏兜底：若消息最终进入死信队列，需由对账任务发现
 * 「已核销但无分账快照」的订单并补记（见 docs 关于对账的说明）。
 */
@Component
public class OrderVerifiedSplitConsumer extends AbstractMqConsumer {

    private final LedgerService ledgerService;

    public OrderVerifiedSplitConsumer(MqIdempotent idempotent, LedgerService ledgerService) {
        super(idempotent);
        this.ledgerService = ledgerService;
    }

    @RabbitListener(queues = MqConstants.FINANCE_SPLIT_QUEUE)
    public void onOrderVerified(Message message, Channel channel) {
        consume(message, channel, msg -> {
            OrderVerifiedEvent event = objectMapper.readValue(msg.getBody(), OrderVerifiedEvent.class);
            if (event.getOrderId() == null || event.getOrderNo() == null) {
                log.warn("分账事件缺少必要字段，已忽略");
                return;
            }
            List<SplitCalculator.LineItem> lines = event.getLines() == null ? List.of()
                    : event.getLines().stream()
                            .map(l -> new SplitCalculator.LineItem(l.getSupplierSubjectId(), l.getAmount()))
                            .toList();

            ledgerService.executeSplit(
                    event.getOrderId(),
                    event.getOrderNo(),
                    event.getPaidAmount() == null ? 0L : event.getPaidAmount(),
                    event.getItemCount() == null ? 0 : event.getItemCount(),
                    event.getStoreSubjectId(),
                    event.getChannelSubjectId(),
                    event.getFirstProductId(),
                    event.getPlatformCommission() == null ? 0L : event.getPlatformCommission(),
                    lines);
            log.info("核销分账完成 orderNo={}", event.getOrderNo());
        });
    }
}
