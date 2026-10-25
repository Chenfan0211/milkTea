package com.wuling.trade.subscribe;

import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.event.SubscribeMessageEvent;
import com.wuling.common.outbox.OutboxService;
import com.wuling.common.subscribe.SubscribeMessageLedger;
import com.wuling.trade.dto.OrderDTO;
import com.wuling.trade.entity.Order;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSubscribeNotifierTest {

    private static final String TEMPLATE_ID = "W-_sFdE4DpQbfs5aeXASyEvVIVkFBYz65Qjl8lQQJWM";

    private WxMiniappSubscribeProperties configured() {
        WxMiniappSubscribeProperties properties = new WxMiniappSubscribeProperties();
        properties.setAppId("wx-test");
        properties.setAppSecret("secret");
        properties.getOrderSubscribe().setEnabled(true);
        properties.getOrderSubscribe().setTemplateId(TEMPLATE_ID);
        return properties;
    }

    @Test
    void enqueuesDurableOutboxEventWithStableBusinessKey() {
        OutboxService outboxService = mock(OutboxService.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        when(ledger.begin("ORDER_PAID:WX202610220001", "order", 7L)).thenReturn(true);

        OrderSubscribeNotifier notifier = new OrderSubscribeNotifier(configured(), outboxService, ledger);
        notifier.enqueueForOrder(order(), dto());

        verify(outboxService).enqueue(
                eq("order"),
                eq("WX202610220001"),
                eq("SUBSCRIBE_ORDER"),
                eq(MqConstants.SUBSCRIBE_ORDER_ROUTING_KEY),
                eq("ORDER_PAID:WX202610220001"),
                any(SubscribeMessageEvent.class));
    }

    @Test
    void doesNotEnqueueDuplicateBusinessKey() {
        OutboxService outboxService = mock(OutboxService.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        when(ledger.begin("ORDER_PAID:WX202610220001", "order", 7L)).thenReturn(false);

        new OrderSubscribeNotifier(configured(), outboxService, ledger).enqueueForOrder(order(), dto());

        verify(outboxService, never()).enqueue(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void skipsWhenDisabled() {
        WxMiniappSubscribeProperties properties = configured();
        properties.getOrderSubscribe().setEnabled(false);
        OutboxService outboxService = mock(OutboxService.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);

        new OrderSubscribeNotifier(properties, outboxService, ledger).enqueueForOrder(order(), dto());

        verify(ledger, never()).begin(anyString(), anyString(), eq(7L));
    }

    @Test
    void failsFastWhenEnabledButConfigMissing() {
        WxMiniappSubscribeProperties properties = configured();
        properties.setAppSecret("");
        OutboxService outboxService = mock(OutboxService.class);
        SubscribeMessageLedger ledger = mock(SubscribeMessageLedger.class);
        OrderSubscribeNotifier notifier = new OrderSubscribeNotifier(properties, outboxService, ledger);

        assertThrows(IllegalStateException.class, () -> notifier.enqueueForOrder(order(), dto()));
    }

    private Order order() {
        Order order = new Order();
        order.setId(1L);
        order.setOrderNo("WX202610220001");
        order.setUserId(7L);
        order.setStatus("PAID");
        order.setPickupCode("A001");
        return order;
    }

    private OrderDTO dto() {
        OrderDTO dto = new OrderDTO();
        dto.setOrderNo("WX202610220001");
        dto.setUserId(7L);
        dto.setStore("系统门店A");
        dto.setSummary("珍珠奶茶");
        return dto;
    }
}
