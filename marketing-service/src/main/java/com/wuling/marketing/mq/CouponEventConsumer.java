package com.wuling.marketing.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.common.mq.event.CouponEvent;
import com.wuling.marketing.service.CouponService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 优惠券状态事件消费者。
 *
 * <p>事件只承载优惠券状态流转，不承载余额扣款等资金动作。消费者按业务动作调用
 * {@link CouponService} 已有的行锁与条件更新方法；AbstractMqConsumer 负责 messageId
 * 幂等、延迟重试与超限死信。</p>
 */
@Component
public class CouponEventConsumer extends AbstractMqConsumer {

    private final CouponService couponService;

    public CouponEventConsumer(MqIdempotent idempotent, CouponService couponService) {
        super(idempotent);
        this.couponService = couponService;
    }

    @RabbitListener(queues = MqConstants.COUPON_EVENT_QUEUE)
    public void onCouponEvent(Message message, Channel channel) {
        ParsedEvent parsed = parseSafely(message);
        String messageId = resolveMessageId(message, parsed);
        prepareMessage(message, messageId, parsed == null ? null : parsed.orderNo());

        consume(message, channel, msg -> {
            ParsedEvent event = parsed == null ? parse(msg) : parsed;
            dispatch(event);
        });
    }

    private void dispatch(ParsedEvent event) {
        switch (event.action()) {
            case CouponEvent.ACTION_CONSUME -> {
                requireUserId(event);
                requireUserCouponId(event);
                couponService.consume(event.userId(), event.userCouponId(), event.orderNo());
            }
            case CouponEvent.ACTION_RELEASE -> {
                requireUserId(event);
                requireUserCouponId(event);
                couponService.release(
                        event.orderNo(),
                        blankToDefault(event.reason(), "订单取消或超时"));
            }
            case CouponEvent.ACTION_RESTORE_REFUND -> {
                requireUserId(event);
                requireUserCouponId(event);
                couponService.restoreAfterRefund(event.userId(), event.userCouponId(), event.orderNo());
            }
            default -> throw new IllegalArgumentException("不支持的优惠券事件 action: " + event.action());
        }
    }

    private void requireUserId(ParsedEvent event) {
        if (event.userId() == null) {
            throw new IllegalArgumentException("优惠券事件缺少 userId");
        }
    }

    private void requireUserCouponId(ParsedEvent event) {
        if (event.userCouponId() == null) {
            throw new IllegalArgumentException("优惠券事件缺少 userCouponId");
        }
    }

    private ParsedEvent parseSafely(Message message) {
        try {
            return parse(message);
        } catch (Exception e) {
            log.warn("优惠券事件 JSON 解析失败，将交由 MQ 重试语义处理 err={}", e.getMessage());
            return null;
        }
    }

    private ParsedEvent parse(Message message) throws Exception {
        JsonNode root = objectMapper.readTree(message.getBody());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("优惠券事件必须是 JSON 对象");
        }
        JsonNode payload = payloadNode(root);
        String action = requiredText(root, payload, "action").toUpperCase(Locale.ROOT);
        String orderNo = requiredText(root, payload, "orderNo");
        Long userId = longValue(root, payload, "userId");
        Long userCouponId = longValue(root, payload, "userCouponId");
        String reason = optionalText(root, payload, "reason");
        return new ParsedEvent(action, userId, userCouponId, orderNo, reason);
    }

    private JsonNode payloadNode(JsonNode root) {
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

    private String resolveMessageId(Message message, ParsedEvent event) {
        String brokerId = headerString(message.getMessageProperties(), MqConstants.HEADER_MESSAGE_ID);
        if (brokerId == null) {
            brokerId = message.getMessageProperties().getMessageId();
        }
        if (hasText(brokerId)) {
            return brokerId.trim();
        }
        String eventId = eventId(message);
        if (hasText(eventId)) {
            return eventId.trim();
        }
        if (event != null) {
            return switch (event.action()) {
                case CouponEvent.ACTION_CONSUME ->
                        "COUPON:CONSUME:" + event.orderNo() + ":" + event.userCouponId();
                case CouponEvent.ACTION_RESTORE_REFUND ->
                        "COUPON:RESTORE_REFUND:" + event.orderNo() + ":" + event.userCouponId();
                case CouponEvent.ACTION_RELEASE -> "COUPON:RELEASE:" + event.orderNo();
                default -> "COUPON:" + event.action() + ":" + event.orderNo();
            };
        }
        return "COUPON:RAW:" + sha256(message.getBody());
    }

    private String eventId(Message message) {
        try {
            JsonNode root = objectMapper.readTree(message.getBody());
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode payload = payloadNode(root);
            return firstText(root.get("eventId"), payload.get("eventId"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void prepareMessage(Message message, String messageId, String orderNo) {
        MessageProperties properties = message.getMessageProperties();
        Map<String, Object> headers = properties.getHeaders();
        if (headers == null) {
            headers = new HashMap<>();
            properties.setHeaders(headers);
        }
        headers.put(MqConstants.HEADER_MESSAGE_ID, messageId);
        properties.setMessageId(messageId);
        if (hasText(orderNo) && !hasText(headerString(properties, MqConstants.HEADER_BIZ_KEY))) {
            headers.put(MqConstants.HEADER_BIZ_KEY, orderNo);
        }
    }

    private String requiredText(JsonNode root, JsonNode payload, String field) {
        String value = optionalText(root, payload, field);
        if (!hasText(value)) {
            throw new IllegalArgumentException("优惠券事件缺少 " + field);
        }
        return value.trim();
    }

    private String optionalText(JsonNode root, JsonNode payload, String field) {
        return firstText(payload.get(field), root.get(field));
    }

    private Long longValue(JsonNode root, JsonNode payload, String field) {
        JsonNode value = firstNode(payload.get(field), root.get(field));
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        if (value.isTextual() && hasText(value.asText())) {
            try {
                return Long.valueOf(value.asText().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("优惠券事件字段 " + field + " 不是合法整数", e);
            }
        }
        throw new IllegalArgumentException("优惠券事件字段 " + field + " 不是合法整数");
    }

    private JsonNode firstNode(JsonNode first, JsonNode second) {
        return first != null && !first.isNull() ? first : second;
    }

    private String firstText(JsonNode first, JsonNode second) {
        String value = textOrNull(first);
        return hasText(value) ? value : textOrNull(second);
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isValueNode() && !node.isNull() ? node.asText() : null;
    }

    private String headerString(MessageProperties properties, String name) {
        Object value = properties.getHeaders().get(name);
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String blankToDefault(String value, String defaultValue) {
        return hasText(value) ? value.trim() : defaultValue;
    }

    private String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(new String(body, StandardCharsets.UTF_8).hashCode());
        }
    }

    private record ParsedEvent(String action,
                               Long userId,
                               Long userCouponId,
                               String orderNo,
                               String reason) {
    }
}
