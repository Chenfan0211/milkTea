package com.wuling.common.subscribe;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscribeMessageLedgerTest {

    @Test
    void beginReturnsTrueOnlyForFirstInsert() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any())).thenReturn(1, 0);
        SubscribeMessageLedger ledger = new SubscribeMessageLedger(jdbcTemplate);

        assertTrue(ledger.begin("ORDER_PAID:1", "order", 9L));
        assertFalse(ledger.begin("ORDER_PAID:1", "order", 9L));
    }

    @Test
    void terminalStatusUsesDatabaseLedger() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(String.class),
                org.mockito.ArgumentMatchers.eq("ORDER_PAID:1"))).thenReturn("SENT");
        assertTrue(new SubscribeMessageLedger(jdbcTemplate).isTerminal("ORDER_PAID:1"));
    }

    @Test
    void markingSentUpdatesAttemptsAndTime() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        new SubscribeMessageLedger(jdbcTemplate).markSent("ORDER_PAID:1", 0, "ok");
        verify(jdbcTemplate).update(anyString(), org.mockito.ArgumentMatchers.eq("SENT"),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq("ok"),
                org.mockito.ArgumentMatchers.eq("SENT"), org.mockito.ArgumentMatchers.eq("ORDER_PAID:1"));
    }
}
