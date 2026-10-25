package com.wuling.common.mq.event;

import java.io.Serializable;
import java.util.Map;

/**
 * 订阅消息队列事件。
 *
 * <p>事件只携带不可变渲染数据，不携带可变实体；消费者只按 userId 查 openid，
 * 然后使用当前模板配置构造微信请求。</p>
 */
public record SubscribeMessageEvent(
        int version,
        String templateKey,
        Long userId,
        Long storeSubjectId,
        String messageKey,
        String page,
        Map<String, Object> values,
        String occurredAt) implements Serializable {

    public static final int CURRENT_VERSION = 1;

    public SubscribeMessageEvent {
        values = values == null ? Map.of() : Map.copyOf(values);
    }
}
