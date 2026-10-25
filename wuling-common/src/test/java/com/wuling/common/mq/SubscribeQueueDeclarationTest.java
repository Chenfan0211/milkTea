package com.wuling.common.mq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscribeQueueDeclarationTest {

    @Test
    void subscriptionQueuesAndDlqsAreDeclared() throws IOException {
        assertEquals("wuling.subscribe.order.queue", MqConstants.SUBSCRIBE_ORDER_QUEUE);
        assertEquals("wuling.subscribe.order", MqConstants.SUBSCRIBE_ORDER_ROUTING_KEY);
        assertEquals(MqConstants.SUBSCRIBE_ORDER_QUEUE + ".dlq", MqConstants.SUBSCRIBE_ORDER_DLQ);
        assertEquals("wuling.subscribe.marketing.queue", MqConstants.SUBSCRIBE_MARKETING_QUEUE);
        assertEquals("wuling.subscribe.marketing", MqConstants.SUBSCRIBE_MARKETING_ROUTING_KEY);
        assertEquals(MqConstants.SUBSCRIBE_MARKETING_QUEUE + ".dlq", MqConstants.SUBSCRIBE_MARKETING_DLQ);
        assertTrue(DlqMonitor.MONITORED.contains(MqConstants.SUBSCRIBE_ORDER_DLQ));
        assertTrue(DlqMonitor.MONITORED.contains(MqConstants.SUBSCRIBE_MARKETING_DLQ));

        String config = Files.readString(Path.of("src/main/java/com/wuling/common/mq/RabbitConfig.java"));
        assertTrue(config.contains("subscribeOrderQueue"));
        assertTrue(config.contains("subscribeMarketingQueue"));
        assertTrue(config.contains("subscribeOrderDlq"));
        assertTrue(config.contains("subscribeMarketingDlq"));
    }
}
