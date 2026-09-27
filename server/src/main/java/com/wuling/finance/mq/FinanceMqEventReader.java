package com.wuling.finance.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuling.common.mq.MqConstants;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * finance MQ 事件读取兼容层。
 *
 * <p>旧生产消息体是裸事件 JSON；event_outbox 迁移后仍发送同一事件负载，但
 * 重放工具或过渡版本可能使用 {@code payload/event} 包装。消费者统一读取内层事件，
 * 并在缺少 Broker messageId 时按订单写入稳定幂等键。
 */
final class FinanceMqEventReader {

    /** 与 MqConstants.HEADER_BIZ_KEY 同协议；保留字面量以兼容旧 common 构件。 */
    private static final String HEADER_BIZ_KEY = "biz-key";

    private FinanceMqEventReader() {
    }

    static <T> T read(ObjectMapper objectMapper, Message message, Class<T> eventType) throws IOException {
        return objectMapper.treeToValue(readPayload(objectMapper, message), eventType);
    }

    static JsonNode readPayload(ObjectMapper objectMapper, Message message) throws IOException {
        JsonNode root = objectMapper.readTree(message.getBody());
        if (root == null || root.isNull()) {
            throw new IOException("MQ 消息体为空");
        }
        JsonNode payload = root.get("payload");
        if (payload != null && payload.isObject()) {
            return payload;
        }
        JsonNode event = root.get("event");
        if (event != null && event.isObject()) {
            return event;
        }
        return root;
    }

    static void ensureStableMessageId(Message message, ObjectMapper objectMapper, String suffix) {
        MessageProperties props = message.getMessageProperties();
        if (hasMessageId(props)) {
            return;
        }

        String messageId = null;
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            if (root != null && root.isObject()) {
                messageId = text(root, "eventId");
                if (!StringUtils.hasText(messageId)) {
                    JsonNode payload = root.get("payload");
                    JsonNode event = payload != null && payload.isObject() ? payload : root.get("event");
                    JsonNode source = event != null && event.isObject() ? event : root;
                    String orderNo = text(source, "orderNo");
                    if (StringUtils.hasText(orderNo)) {
                        messageId = orderNo + ":" + suffix;
                    }
                }
            }
        } catch (IOException ignored) {
            // 消息体格式错误交给 consume 内统一重试/DLQ，不让入口直接逃逸。
        }

        if (!StringUtils.hasText(messageId)) {
            Object bizKey = props.getHeaders().get(HEADER_BIZ_KEY);
            if (bizKey != null && StringUtils.hasText(String.valueOf(bizKey))) {
                messageId = bizKey + ":" + suffix;
            }
        }
        if (StringUtils.hasText(messageId)) {
            props.setMessageId(messageId);
            props.setHeader(MqConstants.HEADER_MESSAGE_ID, messageId);
        }
    }

    private static boolean hasMessageId(MessageProperties props) {
        Object header = props.getHeaders().get(MqConstants.HEADER_MESSAGE_ID);
        return (header != null && StringUtils.hasText(String.valueOf(header)))
                || StringUtils.hasText(props.getMessageId());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}