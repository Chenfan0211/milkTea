package com.wuling.auth;

import com.wuling.auth.service.AdminRbacGuard;
import com.wuling.common.exception.BusinessException;
import com.wuling.security.AdminUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRbacGuardMenuAccessTest {

    private JdbcTemplate jdbcTemplate;
    private AdminRbacGuard guard;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        guard = new AdminRbacGuard(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        AdminUser.clear();
    }

    @Test
    void superAccountBypassesMenuLookup() {
        AdminUser.set(1L, "super");
        when(jdbcTemplate.queryForObject(contains("is_super = 1"), eq(Integer.class), eq(1L))).thenReturn(1);

        assertDoesNotThrow(() -> guard.assertMenuAccess("subject_device", "导入设备"));

        verify(jdbcTemplate, never()).queryForObject(contains("sys_role_menu"), eq(Integer.class), anyLong(), anyString());
    }

    @Test
    void accountWithMenuPermissionPasses() {
        AdminUser.set(2L, "ops");
        when(jdbcTemplate.queryForObject(contains("is_super = 1"), eq(Integer.class), eq(2L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("sys_role_menu"), eq(Integer.class), eq(2L), eq("subject_device")))
                .thenReturn(1);

        assertDoesNotThrow(() -> guard.assertMenuAccess("subject_device", "导入设备"));
    }

    @Test
    void accountWithoutMenuPermissionIsForbidden() {
        AdminUser.set(3L, "viewer");
        when(jdbcTemplate.queryForObject(contains("is_super = 1"), eq(Integer.class), eq(3L))).thenReturn(0);
        when(jdbcTemplate.queryForObject(contains("sys_role_menu"), eq(Integer.class), eq(3L), eq("subject_device")))
                .thenReturn(0);

        assertThrows(BusinessException.class, () -> guard.assertMenuAccess("subject_device", "导入设备"));
    }

    @Test
    void anonymousAccountIsForbiddenWithoutMenuLookup() {
        assertThrows(BusinessException.class, () -> guard.assertMenuAccess("subject_device", "导入设备"));

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Integer.class), any(), any());
    }
}
