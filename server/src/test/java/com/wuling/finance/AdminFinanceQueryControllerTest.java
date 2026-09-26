package com.wuling.finance;

import com.wuling.common.api.PageResult;
import com.wuling.common.api.Result;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.finance.controller.AdminFinanceQueryController;
import com.wuling.finance.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 财务流水查询控制器测试。
 *
 * <p>重点锁定历史 SETTLE 流水归一筛选、结算状态边界和非法类型的业务错误码。
 */
class AdminFinanceQueryControllerTest {

    private JdbcTemplate jdbcTemplate;
    private AdminFinanceQueryController controller;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        controller = new AdminFinanceQueryController(jdbcTemplate, mock(LedgerService.class));

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(3L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    @Test
    void incomeFilterAlsoMatchesHistoricalSettleFlow() {
        Result<PageResult<Map<String, Object>>> result =
                controller.flows(1L, 10L, null, "income", null, null);

        assertNotNull(result.getData());
        assertEquals(3L, result.getData().getTotal());
        assertPageSqlContains("and f.type in ('INCOME', 'SETTLE')");
        assertPageArguments(10L, 0L);
    }

    @Test
    void settledTrueOnlyMatchesSettledFlow() {
        controller.flows(2L, 20L, null, null, null, Boolean.TRUE);

        String pageSql = assertPageSqlContains("and f.settlement_status = 'SETTLED'");
        assertTrue(pageSql.contains(" limit ? offset ?"));
        assertPageArguments(20L, 20L);
    }

    @Test
    void settledFalseMatchesOnlyKnownPendingStatuses() {
        controller.flows(1L, 10L, null, null, null, Boolean.FALSE);

        String pageSql = assertPageSqlContains(
                "and f.settlement_status in ('PENDING', 'SETTLEABLE', 'FROZEN', 'CANCELED')");
        assertFalse(pageSql.contains("f.settlement_status is null"));
        assertPageArguments(10L, 0L);
    }

    @Test
    void invalidTypeThrowsBadRequestBusinessException() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> controller.flows(1L, 10L, null, "TRANSFER", null, null));

        assertEquals(ResultCode.BAD_REQUEST, exception.getCode());
        assertTrue(exception.getMessage().contains("不支持的流水类型"));
        verifyNoInteractions(jdbcTemplate);
    }

    private String assertPageSqlContains(String fragment) {
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(Object[].class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains(fragment), "分页 SQL 应包含筛选条件: " + fragment);
        return sql;
    }

    private void assertPageArguments(long size, long offset) {
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForList(anyString(), argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertEquals(2, args.length);
        assertEquals(size, args[0]);
        assertEquals(offset, args[1]);
    }
}
