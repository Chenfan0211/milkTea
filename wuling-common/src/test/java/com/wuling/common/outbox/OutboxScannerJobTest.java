package com.wuling.common.outbox;

import com.wuling.common.mq.MqProducer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxScannerJobTest {

    @Test
    void shouldMarkSentInBatchOnlyAfterPublishSucceeds() {
        OutboxService service = mock(OutboxService.class);
        MqProducer producer = mock(MqProducer.class);
        OutboxProperties properties = properties();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        EventOutboxEntity first = entity(1L, "E-1", 0, now);
        EventOutboxEntity second = entity(2L, "E-2", 0, now);
        when(service.claimBatch("worker-1", now, 10, 30_000L)).thenReturn(List.of(first, second));
        OutboxScannerJob job = new OutboxScannerJob(
                service, producer, properties, Runnable::run, Clock.fixed(now, ZoneOffset.UTC), "worker-1");

        job.scanOnce();

        verify(producer).sendRawBodyWithId("wuling.payment.success", "{}".getBytes(), "E-1", "O-1");
        verify(producer).sendRawBodyWithId("wuling.payment.success", "{}".getBytes(), "E-2", "O-2");
        verify(service).markSent(List.of(1L, 2L), "worker-1");
        verify(service, never()).markRetry(any(), anyString(), any(Integer.class), any(), anyString());
    }

    @Test
    void shouldPersistIncrementedRetryWithExponentialBackoff() {
        OutboxService service = mock(OutboxService.class);
        MqProducer producer = mock(MqProducer.class);
        OutboxProperties properties = properties();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        EventOutboxEntity event = entity(3L, "E-3", 1, now);
        when(service.claimBatch("worker-1", now, 10, 30_000L)).thenReturn(List.of(event));
        doThrow(new AmqpException("broker unavailable")).when(producer)
                .sendRawBodyWithId(anyString(), any(), anyString(), anyString());
        OutboxScannerJob job = new OutboxScannerJob(
                service, producer, properties, Runnable::run, Clock.fixed(now, ZoneOffset.UTC), "worker-1");

        job.scanOnce();

        verify(service).markRetry(
                eq(event), eq("worker-1"), eq(2), eq(now.plusMillis(200)), contains("broker unavailable"));
        verify(service, never()).markSent(any(), anyString());
    }

    @Test
    void shouldMarkFailedWhenRetryBudgetExhausted() {
        OutboxService service = mock(OutboxService.class);
        MqProducer producer = mock(MqProducer.class);
        OutboxProperties properties = properties();
        properties.setMaxRetries(2);
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        EventOutboxEntity event = entity(4L, "E-4", 1, now);
        when(service.claimBatch("worker-1", now, 10, 30_000L)).thenReturn(List.of(event));
        doThrow(new AmqpException("still failing")).when(producer)
                .sendRawBodyWithId(anyString(), any(), anyString(), anyString());
        OutboxScannerJob job = new OutboxScannerJob(
                service, producer, properties, Runnable::run, Clock.fixed(now, ZoneOffset.UTC), "worker-1");

        job.scanOnce();

        verify(service).markFailed(eq(event), eq("worker-1"), contains("still failing"));
        verify(service, never()).markRetry(any(), anyString(), any(Integer.class), any(), anyString());
    }

    private OutboxProperties properties() {
        OutboxProperties properties = new OutboxProperties();
        properties.setBatchSize(10);
        properties.setLockTimeoutMs(30_000);
        properties.setMaxRetries(5);
        properties.setInitialRetryDelayMs(100);
        properties.setMaxRetryDelayMs(5_000);
        return properties;
    }

    private EventOutboxEntity entity(long id, String eventId, int retryCount, Instant now) {
        return new EventOutboxEntity(id, eventId, "ORDER", "O-" + id, "ORDER_PAID",
                "wuling.payment.success", "O-" + id, "{}", "PUBLISHING", retryCount,
                null, "worker-1", now, null, now, now, now, null);
    }
}