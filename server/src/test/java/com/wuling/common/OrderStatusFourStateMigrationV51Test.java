package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V51：订单四态迁移守卫。 */
class OrderStatusFourStateMigrationV51Test {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");
    private static final Path MIGRATION = MIGRATION_DIR.resolve("V51__order_status_four_state.sql");

    private String normalizedSql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    @Test
    void shouldBackfillOrdersAndGiftCardOrdersBeforeAddingConstraints() throws IOException {
        String sql = normalizedSql();

        String verifiedOrders = "update orders set status = 'completed' where status = 'verified'";
        String refundedOrders = "update orders set status = 'canceled', refund_status = 'refunded' where status = 'refunded'";
        String verifiedGiftCards = "update gift_card_order set status = 'completed' where status = 'verified'";
        String refundingGiftCards = "update gift_card_order set status = 'paid' where status = 'refunding'";

        assertTrue(sql.contains(verifiedOrders), "orders.VERIFIED 应先回填为 COMPLETED");
        assertTrue(sql.contains(refundedOrders), "orders.REFUNDED 应先改为 CANCELED 并回填 refund_status=REFUNDED");
        assertTrue(sql.contains(verifiedGiftCards), "gift_card_order.VERIFIED 应先回填为 COMPLETED");
        assertTrue(sql.contains(refundingGiftCards), "gift_card_order.REFUNDING 应先回填为 PAID");

        int firstConstraint = sql.indexOf("chk_orders_status_four_state");
        assertTrue(firstConstraint > sql.indexOf(verifiedOrders), "orders 回填必须先于 CHECK 约束");
        assertTrue(firstConstraint > sql.indexOf(refundedOrders), "退款订单回填必须先于 CHECK 约束");
        assertTrue(sql.indexOf("chk_gift_card_order_status_four_state") > sql.indexOf(verifiedGiftCards),
                "gift_card_order 回填必须先于 CHECK 约束");
        assertTrue(sql.indexOf("chk_gift_card_order_status_four_state") > sql.indexOf(refundingGiftCards),
                "gift_card_order 退款回填必须先于 CHECK 约束");
    }

    @Test
    void shouldAddFourStateChecksIdempotently() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("from information_schema.table_constraints"),
                "CHECK 约束必须按 information_schema.table_constraints 判断存在性");
        assertTrue(sql.contains("table_schema = database()"), "存在性检查必须限定当前数据库");
        assertTrue(sql.contains("table_name = 'orders'"), "应检查 orders 表");
        assertTrue(sql.contains("table_name = 'gift_card_order'"), "应检查 gift_card_order 表");
        assertTrue(sql.contains("constraint_name = 'chk_orders_status_four_state'"),
                "应使用约定的 orders 约束名");
        assertTrue(sql.contains("constraint_name = 'chk_gift_card_order_status_four_state'"),
                "应使用约定的 gift_card_order 约束名");

        assertTrue(sql.contains("add constraint chk_orders_status_four_state check (status in (''created'', ''paid'', ''completed'', ''canceled''))"),
                "orders.status 只允许四态");
        assertTrue(sql.contains("add constraint chk_gift_card_order_status_four_state check (status in (''created'', ''paid'', ''completed'', ''canceled''))"),
                "gift_card_order.status 只允许四态");

        assertTrue(countOccurrences(sql, "set @exist :=") >= 2, "每张表都应先检查约束是否存在");
        assertTrue(countOccurrences(sql, "prepare stmt from @ddl") >= 2, "DDL 应通过动态 SQL 幂等执行");
        assertTrue(countOccurrences(sql, "execute stmt") >= 2, "动态 DDL 应执行");
        assertTrue(countOccurrences(sql, "deallocate prepare stmt") >= 2, "动态 DDL 应清理 prepared statement");
    }

    @Test
    void shouldBeRepeatSafeAndNeverTouchFlywayHistory() throws IOException {
        String sql = normalizedSql();

        assertFalse(sql.contains("flyway_schema_history"), "严禁手工写 Flyway 历史");
        assertFalse(sql.contains("add constraint if not exists"), "MySQL 8 不应依赖 ADD CONSTRAINT IF NOT EXISTS");
        assertFalse(sql.contains("drop constraint"), "重复执行不应删除并重建业务约束");
    }

    private int countOccurrences(String source, String token) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }
}
