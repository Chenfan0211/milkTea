package com.wuling.subject.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

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
 * 因此把口径固定成测试：
 * <ol>
 *   <li>{@code user_role_grant} 命中即放行，且不再查 app_user；</li>
 *   <li>授权表未命中时回落到 {@code app_user.bound_subject_id} + 经营角色；</li>
 *   <li>参数缺失直接拒绝，且不访问数据库。</li>
 * </ol>
 *
 * <p>补充（多角色共存）：授权表是唯一真相源，其 role_code / status 必须
 * 大小写归一后比较，与 {@code AppRoleController#mine} 的 equalsIgnoreCase 口径一致；
 * 否则历史小写存量会导致「小程序能看到门店角色、接口却判定无权」。
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

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("UPPER(business_role) = 'STORE'")),
                "绑定回落查询必须 UPPER 归一 business_role");
    }

    @Test
    @DisplayName("授权表查询必须大小写归一 role_code 与 status")
    void grantQueryNormalizesRoleCodeAndStatus() {
        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        service.canOperate(7L, 101L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), any(Object[].class));
        String grantSql = sql.getValue();
        assertTrue(grantSql.contains("UPPER(role_code) = 'STORE'"),
                "role_code 必须 UPPER 归一，与 AppRoleController 口径一致");
        assertTrue(grantSql.contains("UPPER(status) = 'ACTIVE'"),
                "status 必须 UPPER 归一，避免历史小写导致漏判");
    }

    @Test
    @DisplayName("用户多角色时，持有 STORE 角色即可经营该门店（不依赖主主体）")
    void multiRoleUserStoresRoleGrantsAccessWithoutMainSubject() {
        // 授权表命中（该用户持有 STORE@101），主主体指向别处（如 INVESTOR）
        when(jdbcTemplate.queryForObject(contains("from user_role_grant"), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.canOperate(7L, 101L),
                "多角色用户的门店权限应以 user_role_grant 为准，不受主主体影响");
        verify(jdbcTemplate, never())
                .queryForObject(contains("from app_user"), eq(Long.class), any(Object[].class));
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
