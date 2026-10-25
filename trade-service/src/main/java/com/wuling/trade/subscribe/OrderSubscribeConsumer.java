package com.wuling.trade.subscribe;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import com.wuling.trade.port.UserQueryPort;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 下单成功订阅消息消费者。 */
@Component
public class OrderSubscribeConsumer extends AbstractMqConsumer {

    private final SubscribeMessageLedger ledger;
    private final UserQueryPort userQueryPort;
    private final OrderSubscribeMessageClient client;

    public OrderSubscribeConsumer(MqIdempotent idempotent,
                                  SubscribeMessageLedger ledger,
                                  UserQueryPort userQueryPort,
                                  OrderSubscribeMessageClient client) {
        super(idempotent);
        this.ledger = ledger;
        this.userQueryPort = userQueryPort;
        this.client = client;
    }

    @RabbitListener(queues = MqConstants.SUBSCRIBE_ORDER_QUEUE)
    public void onMessage(Message message, Channel channel) {
        consumeAfterSuccess(message, channel, this::handle);
    }

    private void handle(Message message) throws Exception {
        SubscribeMessageEvent event = objectMapper.readValue(message.getBody(), SubscribeMessageEvent.class);
        if (!StringUtils.hasText(event.messageKey())) {
            throw new IllegalArgumentException("订阅事件 messageKey 不能为空");
        }
        if (ledger.isTerminal(event.messageKey())) {
            return;
        }
        String openid = userQueryPort.findOpenid(event.userId());
        if (!StringUtils.hasText(openid)) {
            ledger.markSkipped(event.messageKey(), -1, "用户 openid 不存在");
            return;
        }

        OrderSubscribeMessageClient.SendResult result = client.sendEvent(
                event.values(), event.page(), openid);
        if (result.errcode() == 0) {
            ledger.markSent(event.messageKey(), result.errcode(), result.errmsg());
            return;
        }
        if (result.errcode() == 43101) {
            ledger.markSkipped(event.messageKey(), result.errcode(), result.errmsg());
            return;
        }
        ledger.markFailed(event.messageKey(), result.errcode(), result.errmsg());
        throw new IllegalStateException("下单订阅消息发送失败 errcode=" + result.errcode());
    }
}
