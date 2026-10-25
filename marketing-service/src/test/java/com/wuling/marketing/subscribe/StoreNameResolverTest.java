package com.wuling.marketing.subscribe;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoreNameResolverTest {

    @Test
    void returnsLatestSystemStoreName() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of("系统门店A"));
        assertEquals("系统门店A", new StoreNameResolver(jdbcTemplate).resolve(101L));
    }

    @Test
    void returnsNullWhenStoreIsMissingOrInvalid() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), any())).thenReturn(List.of());
        assertNull(new StoreNameResolver(jdbcTemplate).resolve(101L));
        assertNull(new StoreNameResolver(jdbcTemplate).resolve(null));
    }
}
