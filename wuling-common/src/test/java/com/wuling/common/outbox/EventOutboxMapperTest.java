package com.wuling.common.outbox;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

        assertTrue(updated == 2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertTrue(sql.getValue().contains("status='PUBLISHING'"));
        assertTrue(sql.getValue().contains("locked_by=?"));
        assertArrayEquals(new Object[]{"SENT", java.sql.Timestamp.from(sentAt), "worker-1", 11L, 12L}, args.getValue());
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
        assertArrayEquals(new Object[]{"NEW", 1, java.sql.Timestamp.from(nextRetryAt), "broker unavailable", java.sql.Timestamp.from(now), 21L, "worker-1"}, args.getValue());
    }
}
