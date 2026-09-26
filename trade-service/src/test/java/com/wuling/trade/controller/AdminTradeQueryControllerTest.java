package com.wuling.trade.controller;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.trade.service.PaymentRetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminTradeQueryControllerTest {

    private JdbcTemplate jdbcTemplate;
    private AdminTradeQueryController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new AdminTradeQueryController(jdbcTemplate, mock(PaymentRetryService.class));
    }

    @Test
    void paginationUsesSingleUnionQueryWithLimitOffset() {
        stubTotals(1L, 1L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Result<PageResult<Map<String, Object>>> result =
                controller.verifyPool(2, 10, null, null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(10L), eq(10L));
        assertTrue(sqlCaptor.getValue().toLowerCase().contains("union all"));
        assertTrue(sqlCaptor.getValue().toLowerCase().contains("limit ? offset ?"));
        assertEquals(2L, result.getData().getTotal());
    }

    @Test
    void orderTypeOnlyCountsOrderPool() {
        stubTotals(3L, 7L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Result<PageResult<Map<String, Object>>> result =
                controller.verifyPool(1, 10, null, "order", null);

        assertEquals(3L, result.getData().getTotal());
        verify(jdbcTemplate, never()).queryForObject(
                contains("exchange_order"), eq(Long.class), any(Object[].class));
    }

    @Test
    void exchangeTypeOnlyCountsExchangePool() {
        stubTotals(3L, 7L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Result<PageResult<Map<String, Object>>> result =
                controller.verifyPool(1, 10, null, "exchange", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), eq(10L), eq(0L));
        assertEquals(7L, result.getData().getTotal());
        assertFalse(sqlCaptor.getValue().toLowerCase().contains("from orders"));
        verify(jdbcTemplate, never()).queryForObject(
                contains("from orders"), eq(Long.class), any(Object[].class));
    }
    @Test
    void poolConditionsAreFlattenedBeforePaginationArgs() {
        stubTotals(1L, 1L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        controller.verifyPool(3, 20, "A", null, 9L);

        verify(jdbcTemplate).queryForList(
                anyString(),
                eq(9L), eq("%A%"), eq("%A%"),
                eq("%A%"), eq("%A%"),
                eq(20L), eq(40L));
    }

    @Test
    void mapsOrderAndExchangeRowsWithoutChangingResponseContract() {
        stubTotals(1L, 1L);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(orderRow());
        rows.add(exchangeRow());
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(rows);

        PageResult<Map<String, Object>> page =
                controller.verifyPool(1, 10, null, null, null).getData();

        Map<String, Object> order = page.getRecords().stream()
                .filter(row -> "order".equals(row.get("type")))
                .findFirst().orElseThrow();
        assertEquals("o-11", order.get("id"));
        assertEquals("MO202609260001", order.get("orderNo"));
        assertEquals("0001", order.get("pickupCode"));
        assertNull(order.get("pickupCodeFromDb"));
        assertEquals("美式 x1", order.get("product"));
        assertEquals("中杯", order.get("spec"));
        assertEquals(1280L, order.get("amount"));
        assertNull(order.get("points"));
        assertEquals(5L, order.get("storeSubjectId"));
        assertEquals("2026-09-26 10:00:00", order.get("createTime"));

        Map<String, Object> exchange = page.getRecords().stream()
                .filter(row -> "exchange".equals(row.get("type")))
                .findFirst().orElseThrow();
        assertEquals("e-22", exchange.get("id"));
        assertEquals("EX202609260022", exchange.get("orderNo"));
        assertEquals("E022", exchange.get("pickupCode"));
        assertEquals("E022", exchange.get("pickupCodeFromDb"));
        assertEquals("积分商品", exchange.get("product"));
        assertNull(exchange.get("spec"));
        assertEquals(0L, exchange.get("amount"));
        assertEquals(300L, exchange.get("points"));
        assertNull(exchange.get("storeSubjectId"));
    }

    @Test
    void emptyPoolReturnsEmptyPageWithoutDatabaseError() {
        stubTotals(0L, 0L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        PageResult<Map<String, Object>> page =
                controller.verifyPool(1, 10, null, null, null).getData();

        assertTrue(page.getRecords().isEmpty());
        assertEquals(0L, page.getTotal());
    }

    private void stubTotals(long orderTotal, long exchangeTotal) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    return sql.contains("exchange_order") ? exchangeTotal : orderTotal;
                });
    }

    private Map<String, Object> orderRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_id", 11L);
        row.put("row_type", "order");
        row.put("order_no", "MO202609260001");
        row.put("store_subject_id", 5L);
        row.put("pickup_code", null);
        row.put("paid_amount", 1280L);
        row.put("points", null);
        row.put("summary", "美式 x1");
        row.put("specs", "中杯");
        row.put("create_time", "2026-09-26 10:00:00");
        return row;
    }

    private Map<String, Object> exchangeRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_id", 22L);
        row.put("row_type", "exchange");
        row.put("order_no", "EX202609260022");
        row.put("store_subject_id", null);
        row.put("pickup_code", "E022");
        row.put("paid_amount", 0L);
        row.put("points", 300L);
        row.put("summary", "积分商品");
        row.put("specs", null);
        row.put("create_time", "2026-09-26 09:00:00");
        return row;
    }
}
