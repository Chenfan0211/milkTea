package com.wuling.marketing.subscribe;

import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.outbox.OutboxService;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketingSubscribeNotifierTest {

    @Test
    void enqueuesOutboxWithoutCallingWechat() {
        OutboxService outboxService = mock(OutboxService.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        when(ledger.begin("POINTS_CHANGE:1", "pointsChange", 9L)).thenReturn(true);

        new MarketingSubscribeNotifier(configured(), outboxService, ledger)
                .notifyPointsChange(9L, 101L, 10L, 110L, "每日签到",
                        LocalDateTime.of(2026, 10, 22, 10, 0), "POINTS_CHANGE:1");

        verify(outboxService).enqueue(
                eq("subscribe"),
                eq("pointsChange"),
                eq("SUBSCRIBE_MARKETING"),
                eq(MqConstants.SUBSCRIBE_MARKETING_ROUTING_KEY),
                eq("POINTS_CHANGE:1"),
                any(SubscribeMessageEvent.class));
    }

    @Test
    void skipsDuplicateMessageKey() {
        OutboxService outboxService = mock(OutboxService.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        when(ledger.begin("POINTS_CHANGE:1", "pointsChange", 9L)).thenReturn(false);

        new MarketingSubscribeNotifier(configured(), outboxService, ledger)
                .notifyPointsChange(9L, 101L, 10L, 110L, "每日签到",
                        LocalDateTime.now(), "POINTS_CHANGE:1");

        verify(outboxService, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void failsFastWhenEnabledTemplateMissing() {
        WxMarketingSubscribeProperties properties = configured();
        properties.getSubscribe().getPointsChange().setTemplateId("");
        MarketingSubscribeNotifier notifier = new MarketingSubscribeNotifier(
                properties, mock(OutboxService.class), mock(SubscribeMessageLedger.class));

        assertThrows(IllegalStateException.class, () -> notifier.notifyPointsChange(
                9L, 101L, 10L, 110L, "每日签到", LocalDateTime.now(), "POINTS_CHANGE:1"));
    }

    private WxMarketingSubscribeProperties configured() {
        WxMarketingSubscribeProperties properties = new WxMarketingSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret");
        properties.getSubscribe().setEnabled(true);
        properties.getSubscribe().getPointsChange().setTemplateId("template-points");
        properties.getSubscribe().getPointsChange().setPage("pages/points-detail/points-detail");
        return properties;
    }
}
