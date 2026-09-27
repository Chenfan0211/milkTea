package com.wuling.common.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventOutboxMapperTest {

    @Test
    void markSentUpdatesWholeBatchOnlyForRowsClaimedByOwner() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EventOutboxMapper mapper = new EventOutboxMapper(jdbcTemplate);
        Instant sentAt = Instant.parse("2026-09-27T10:00:00Z");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int updated = mapper.markSent(List.of(11L, 12L), "worker-1", sentAt);

        assertEquals(2, updated);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("status='PUBLISHING'"));
        assertTrue(sql.getValue().contains("locked_by=?"));
        assertArrayEquals(new Object[]{"SENT", Timestamp.from(sentAt), "worker-1", 11L, 12L}, args.getValue());
    }

    @Test
    void markRetryRestoresNewStatusWithBackoffAndConditionalOwnership() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EventOutboxMapper mapper = new EventOutboxMapper(jdbcTemplate);
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        Instant nextRetryAt = now.plusSeconds(10);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        mapper.markRetry(21L, "worker-1", 1, nextRetryAt, "broker unavailable", now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("status=?"));
        assertTrue(sql.getValue().contains("retry_count=?"));
        assertTrue(sql.getValue().contains("locked_by=?"));
        assertArrayEquals(new Object[]{"NEW", 1, Timestamp.from(nextRetryAt), "broker unavailable", Timestamp.from(now), 21L, "worker-1"}, args.getValue());
    }

    @Test
    void claimBatchReclaimsStalePublishingRowsWithConditionalAtomicUpdate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EventOutboxMapper mapper = new EventOutboxMapper(jdbcTemplate);
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        Instant staleBefore = now.minusSeconds(30);
        EventOutboxEntity stale = publishingEvent(31L, "worker-old", staleBefore.minusSeconds(1), now);
        when(jdbcTemplate.query(contains("status = 'PUBLISHING'"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(stale));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        List<EventOutboxEntity> claimed = mapper.claimBatch("worker-new", now, 10, staleBefore);

        assertEquals(1, claimed.size());
        assertEquals(OutboxStatus.PUBLISHING, claimed.get(0).status());
        assertEquals("worker-new", claimed.get(0).lockedBy());
        assertEquals(now, claimed.get(0).lockedAt());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("status = 'NEW'"));
        assertTrue(sql.getValue().contains("status = 'PUBLISHING'"));
        assertTrue(sql.getValue().contains("locked_at IS NOT NULL"));
        assertTrue(sql.getValue().contains("locked_at <= ?"));
        assertArrayEquals(new Object[]{
                "worker-new", Timestamp.from(now), Timestamp.from(now), 31L,
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(staleBefore)
        }, args.getValue());
    }

    @Test
    void claimBatchReturnsEmptyWhenConditionalUpdateLosesClaimRace() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        EventOutboxMapper mapper = new EventOutboxMapper(jdbcTemplate);
        Instant now = Instant.parse("2026-09-27T10:00:00Z");
        Instant staleBefore = now.minusSeconds(30);
        EventOutboxEntity stale = publishingEvent(41L, "worker-old", staleBefore.minusSeconds(1), now);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(stale));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        List<EventOutboxEntity> claimed = mapper.claimBatch("worker-new", now, 10, staleBefore);

        assertTrue(claimed.isEmpty());
    }

    private EventOutboxEntity publishingEvent(long id, String lockedBy, Instant lockedAt, Instant availableAt) {
        return new EventOutboxEntity(
                id, "E-" + id, "ORDER", "O-" + id, "ORDER_PAID",
                "wuling.payment.success", "O-" + id, "{}", OutboxStatus.PUBLISHING, 0,
                null, lockedBy, lockedAt, "stale publishing row", availableAt,
                availableAt, availableAt, null);
    }
}
