package com.wuling.trade.subscribe;

import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.outbox.OutboxService;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * 支付成功后的“下单成功”订阅消息生产者。
 *
 * <p>支付事务内登记发送账本和 event_outbox；微信发送由订阅队列异步完成。
 * 入队失败会抛回业务事务，避免业务成功但发送任务静默丢失。</p>
 */
@Component
public class OrderSubscribeNotifier {

    private final WxMiniappSubscribeProperties properties;
    private final OutboxService outboxService;
    private final SubscribeMessageLedger ledger;

    public OrderSubscribeNotifier(WxMiniappSubscribeProperties properties,
                                  OutboxService outboxService,
                                  SubscribeMessageLedger ledger) {
        this.properties = properties;
        this.outboxService = outboxService;
        this.ledger = ledger;
    }

    /** 在当前事务内登记订单订阅消息。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueForOrder(Order order, OrderDTO dto) {
        if (order == null || order.getUserId() == null || !StringUtils.hasText(order.getOrderNo())) {
            return;
        }
        if (!properties.getOrderSubscribe().isEnabled()) {
            return;
        }
        if (!isConfigured()) {
            throw new IllegalStateException("下单订阅消息已启用但配置不完整");
        }

        String messageKey = "ORDER_PAID:" + order.getOrderNo();
        if (!ledger.begin(messageKey, "order", order.getUserId())) {
            return;
        }

        SubscribeMessageEvent event = new SubscribeMessageEvent(
                SubscribeMessageEvent.CURRENT_VERSION,
                "order",
                order.getUserId(),
                null,
                messageKey,
                OrderSubscribeMessageClient.buildEventPage(properties.getOrderSubscribe().getPage(), order),
                OrderSubscribeMessageClient.buildEventValues(order, dto),
                Instant.now().toString());
        outboxService.enqueue(
                "order",
                order.getOrderNo(),
                "SUBSCRIBE_ORDER",
                MqConstants.SUBSCRIBE_ORDER_ROUTING_KEY,
                messageKey,
                event);
    }

    private boolean isConfigured() {
        return StringUtils.hasText(properties.getAppId())
                && StringUtils.hasText(properties.getAppSecret())
                && StringUtils.hasText(properties.getOrderSubscribe().getTemplateId());
    }
}
