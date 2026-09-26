package com.wuling.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceTest {

    @Test
    void enqueueSerializesPayloadAndUsesCallerTransaction() {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        OutboxService service = new OutboxService(mapper, new ObjectMapper());

        String eventId = service.enqueue(
                "ORDER", "O-1", "ORDER_PAID", "wuling.payment.success", "O-1",
                Map.of("amount", 100));

        ArgumentCaptor<EventOutboxEntity> captor = ArgumentCaptor.forClass(EventOutboxEntity.class);
        verify(mapper).insert(captor.capture());
        EventOutboxEntity event = captor.getValue();
        assertEquals(eventId, event.eventId());
        assertEquals("ORDER", event.aggregateType());
        assertEquals("O-1", event.aggregateId());
        assertEquals("ORDER_PAID", event.eventType());
        assertEquals("wuling.payment.success", event.routingKey());
        assertEquals("O-1", event.bizKey());
        assertEquals("{\"amount\":100}", event.payload());
        assertEquals("NEW", event.status());
        assertEquals(0, event.retryCount());
    }

    @Test
    void claimBatchDelegatesToMapperForAtomicClaim() {
        EventOutboxMapper mapper = mock(EventOutboxMapper.class);
        OutboxService service = new OutboxService(mapper, new ObjectMapper());
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        EventOutboxEntity event = entity(1L, "E-1", 0);
        when(mapper.claimBatch("worker-1", now, 10)).thenReturn(List.of(event));

        List<EventOutboxEntity> claimed = service.claimBatch("worker-1", now, 10);

        assertEquals(List.of(event), claimed);
        verify(mapper).claimBatch("worker-1", now, 10);
    }

    private EventOutboxEntity entity(long id, String eventId, int retryCount) {
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        return new EventOutboxEntity(id, eventId, "ORDER", "O-1", "ORDER_PAID",
                "wuling.payment.success", "O-1", "{}", "PUBLISHING", retryCount,
                null, "worker-1", now, null, now, now, now, null);
    }
}