package com.wuling.subject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 门店经营判定测试（不依赖数据库，全部走 mock）。
 *
 * <p>这段判定是所有门店侧数据接口的越权防线（核销 / 门店选品 / 设备与冲泡记录），
 * 因此把三条口径固定成测试：
 * <ol>
 *   <li>{@code user_role_grant} 命中即放行，且不再查 app_user（少一次无谓查询）；</li>
 *   <li>授权表未命中时回落到 {@code app_user.bound_subject_id} + 经营角色；</li>
 *   <li>参数缺失直接拒绝，且不访问数据库。</li>
 * </ol>
 */
class StoreOperatorServiceTest {

    private JdbcTemplate jdbcTemplate;
    private StoreOperatorService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new StoreOperatorService(jdbcTemplate);
    }

    @Test
    void grantRecordAllowsOperation() {
        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.canOperate(7L, 101L));
        // 授权表已放行，无需再查绑定表
        verify(jdbcTemplate, never())
                .queryForObject(contains("from app_user"), eq(Long.class), any(Object[].class));
    }

    @Test
    void boundSubjectFallbackAllowsOperation() {
        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForObject(contains("from app_user"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.canOperate(7L, 101L));
    }

    @Test
    void boundFallbackReadsBusinessRoleCaseInsensitively() {
        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);
        when(jdbcTemplate.queryForObject(contains("from app_user"), eq(Long.class), any(Object[].class)))
                .thenReturn(0L);

        service.canOperate(7L, 101L);

        // 历史存量出现过小写 business_role，必须 UPPER 归一（V72 迁移同口径）。
        // 两次查询分别是「授权表」与「绑定回落」，这里统一抓取后只断言回落那条。
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("UPPER(business_role) = 'STORE'")),
                "绑定回落查询必须 UPPER 归一 business_role");
    }

    @Test
    void neitherGrantNorBindingDenies() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), any(Object[].class))).thenReturn(0L);

        assertFalse(service.canOperate(7L, 101L));
    }

    @Test
    void missingArgumentsDenyWithoutTouchingDatabase() {
        assertFalse(service.canOperate(null, 101L));
        assertFalse(service.canOperate(7L, null));
        assertFalse(service.canOperate(null, null));

        verifyNoInteractions(jdbcTemplate);
    }
}
