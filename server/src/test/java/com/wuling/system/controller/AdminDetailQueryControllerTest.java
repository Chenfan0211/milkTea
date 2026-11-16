package com.wuling.system.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminDetailQueryControllerTest {

    @Test
    void snapshotDetailExposesOrderTypeAndLeaderAmount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "id", 11L,
                        "snapshot_no", "SN-MALL",
                        "order_no", "MO-MALL",
                        "order_type", "MALL",
                        "item_count", 2,
                        "platform_amount", 1200L,
                        "supplier_amount", 800L,
                        "leader_amount", 200L
                )), List.of());

        Map<String, Object> row = new AdminDetailQueryController(jdbcTemplate)
                .snapshotDetail(11L)
                .getData();

        assertEquals("MALL", row.get("orderType"));
        assertEquals(200L, row.get("leaderAmount"));
    }
}
