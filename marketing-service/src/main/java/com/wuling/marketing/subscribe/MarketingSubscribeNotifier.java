package com.wuling.marketing.subscribe;

import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.outbox.OutboxService;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 营销订阅消息生产者：只登记账本与 outbox，不直接调用微信。 */
@Component
public class MarketingSubscribeNotifier {

    private final WxMarketingSubscribeProperties properties;
    private final OutboxService outboxService;
    private final SubscribeMessageLedger ledger;

    public MarketingSubscribeNotifier(WxMarketingSubscribeProperties properties,
                                      OutboxService outboxService,
                                      SubscribeMessageLedger ledger) {
        this.properties = properties;
        this.outboxService = outboxService;
        this.ledger = ledger;
    }

    public void notifyPointsChange(Long userId, Long storeSubjectId, long amount, long balanceAfter,
                                   String reason, LocalDateTime time, String messageKey) {
        Map<String, Object> values = Map.of(
                "amount", amount,
                "balanceAfter", balanceAfter,
                "reason", reason == null ? "时光币变动" : reason,
                "time", (time == null ? LocalDateTime.now() : time).toString());
        schedule("pointsChange", messageKey, userId, storeSubjectId, values);
    }

    public void notifyPointsExchange(Long userId, String productName, int quantity, long points,
                                     long balanceAfter, LocalDateTime time, String messageKey) {
        Map<String, Object> values = Map.of(
                "productName", productName == null ? "兑换商品" : productName,
                "quantity", quantity,
                "points", points,
                "balanceAfter", balanceAfter,
                "time", (time == null ? LocalDateTime.now() : time).toString());
        schedule("pointsExchange", messageKey, userId, null, values);
    }

    public void notifyStoredValueRecharge(Long userId, Long storeSubjectId, long amountFen,
                                          LocalDateTime time, String orderNo) {
        Map<String, Object> values = Map.of(
                "amountFen", amountFen,
                "time", (time == null ? LocalDateTime.now() : time).toString());
        schedule("storedValueRecharge", "STORED_VALUE_RECHARGE:" + orderNo,
                userId, storeSubjectId, values);
    }

    public void notifyBalanceChange(Long userId, Long storeSubjectId, long amountFen,
                                    long balanceAfterFen, String reason, LocalDateTime time,
                                    String messageKey) {
        Map<String, Object> values = Map.of(
                "amountFen", amountFen,
                "balanceAfterFen", balanceAfterFen,
                "reason", reason == null ? "余额变动" : reason,
                "time", (time == null ? LocalDateTime.now() : time).toString());
        schedule("balanceChange", messageKey, userId, storeSubjectId, values);
    }

    public void notifyCouponArrival(Long userId, String couponName, int quantity,
                                    LocalDateTime expireAt, String remark, String messageKey) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("couponName", couponName == null ? "优惠券" : couponName);
        values.put("quantity", quantity);
        values.put("expireAt", (expireAt == null ? LocalDateTime.now() : expireAt).toString());
        values.put("remark", remark == null ? "已放入券包" : remark);
        schedule("couponArrival", messageKey, userId, null, values);
    }

    public void notifyCouponExpiry(Long userId, String couponName, long amountFen,
                                   LocalDateTime expireAt, String tip, String messageKey) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("couponName", couponName == null ? "优惠券" : couponName);
        values.put("amountFen", amountFen);
        values.put("expireAt", (expireAt == null ? LocalDateTime.now() : expireAt).toString());
        values.put("tip", tip == null ? "请及时使用" : tip);
        schedule("couponExpiry", messageKey, userId, null, values);
    }

    private void schedule(String templateKey, String messageKey, Long userId,
                          Long storeSubjectId, Map<String, Object> values) {
        if (userId == null || !StringUtils.hasText(messageKey)) {
            return;
        }
        if (!isConfigured(templateKey)) {
            return;
        }
        if (!ledger.begin(messageKey, templateKey, userId)) {
            return;
        }
        SubscribeMessageEvent event = new SubscribeMessageEvent(
                SubscribeMessageEvent.CURRENT_VERSION,
                templateKey,
                userId,
                storeSubjectId,
                messageKey,
                null,
                values,
                Instant.now().toString());
        outboxService.enqueue(
                "subscribe",
                templateKey,
                "SUBSCRIBE_MARKETING",
                MqConstants.SUBSCRIBE_MARKETING_ROUTING_KEY,
                messageKey,
                event);
    }

    private boolean isConfigured(String templateKey) {
        WxMarketingSubscribeProperties.TemplateConfig config = properties.template(templateKey);
        if (!properties.getSubscribe().isEnabled() || !config.isEnabled()) {
            return false;
        }
        if (!StringUtils.hasText(properties.getAppId())
                || !StringUtils.hasText(properties.getAppSecret())
                || !StringUtils.hasText(config.getTemplateId())) {
            throw new IllegalStateException("营销订阅消息已启用但配置不完整: " + templateKey);
        }
        return true;
    }
}
