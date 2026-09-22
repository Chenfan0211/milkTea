package com.wuling.common.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SqlGuard 单元测试：验证 SQL 注入防护的关键边界 */
class SqlGuardTest {

    // ---------- ident ----------

    @Test
    void identShouldAcceptValidNames() {
        assertEquals("orders", SqlGuard.ident("orders"));
        assertEquals("order_item", SqlGuard.ident("order_item"));
        assertEquals("a", SqlGuard.ident("a"));
        assertEquals("t1_abc_2024", SqlGuard.ident("t1_abc_2024"));
    }

    @Test
    void identShouldRejectSqlInjectionPayloads() {
        String[] payloads = {
                "orders; drop table users",
                "orders--",
                "orders/*x*/",
                "1=1",
                "orders where 1=1",
                "orders`",
                "orders'",
                "orders\"",
                "(select 1)",
                "orders union select password from sys_user"
        };
        for (String p : payloads) {
            assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident(p),
                    "应拒绝注入载荷: " + p);
        }
    }

    @Test
    void identShouldRejectNullOrBlank() {
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident(null));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident(""));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident("  "));
    }

    @Test
    void identShouldEnforceNamingRules() {
        // 首字符必须是字母（防 "1abc" 这类）
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident("1orders"));
        // 不允许大写（统一小写，防止绕过比对）
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident("Orders"));
        // 不允许连字符、点号
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident("order-item"));
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident("db.orders"));
        // 超长（>64）
        assertThrows(IllegalArgumentException.class, () -> SqlGuard.ident("a".repeat(65)));
    }

    // ---------- orderBy ----------

    @Test
    void orderByShouldNormalizeValidClauses() {
        assertEquals("sort asc, id desc", SqlGuard.orderBy("sort asc, id desc"));
        // 大小写与多余空格应被规范化
        assertEquals("sort asc, id desc", SqlGuard.orderBy("  SORT   ASC ,  ID  DESC  "));
        assertEquals("id asc", SqlGuard.orderBy("id asc"));
    }

    @Test
    void orderByShouldRejectInjection() {
        // order by 是最容易拼接失守的位置，重点覆盖
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy("id; drop table orders"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy("(select 1) asc"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy("id asc, name desc, 1=1"));
        // 方向白名单
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy("id ascending"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy("id"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy("id asc limit 1"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy(""));
        assertThrows(IllegalArgumentException.class,
                () -> SqlGuard.orderBy(null));
    }

    // ---------- isValid ----------

    @Test
    void isValidShouldNotThrow() {
        assertTrue(SqlGuard.isValid("orders"));
        assertFalse(SqlGuard.isValid("orders;"));
        assertFalse(SqlGuard.isValid(null));
    }
}
