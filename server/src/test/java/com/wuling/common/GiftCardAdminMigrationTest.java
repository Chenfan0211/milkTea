package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V48 礼品卡后台管理迁移守卫。 */
class GiftCardAdminMigrationTest {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V48__gift_card_admin_management.sql");

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V48 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void shouldCreateGiftCardGroupTable() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("create table gift_card_group"), "应创建 gift_card_group");
        assertTrue(lower.contains("code"), "分组表应有 code");
        assertTrue(lower.contains("name"), "分组表应有 name");
        assertTrue(lower.contains("sort"), "分组表应有 sort");
        assertTrue(lower.contains("deleted"), "分组表应有 deleted");
        assertTrue(lower.contains("create_time"), "分组表应有 create_time");
        assertTrue(lower.contains("update_time"), "分组表应有 update_time");
        assertFalse(lower.contains("flyway_schema_history"), "不得手工伪造 Flyway 历史");
    }

    @Test
    void shouldBackfillPopularAndLimitedGroups() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("insert into gift_card_group"), "应回填现有分组");
        assertTrue(lower.contains("from gift_card_denomination"), "应从未改名的原表回填");
        assertTrue(lower.contains("group_id in ('popular', 'limited')")
                        || lower.contains("group_id in ('limited', 'popular')"),
                "只回填 popular/limited 两个已知分组");
        assertTrue(lower.contains("group by group_id"), "应按 group_id 聚合回填");
    }

    @Test
    void shouldAddAggregateQueryIndex() throws IOException {
        String lower = sql().toLowerCase().replaceAll("\\s+", " ");
        assertTrue(lower.contains("alter table gift_card_denomination"),
                "应给 gift_card_denomination 添加聚合索引");
        assertTrue(lower.contains("idx_gift_card_denomination_face"),
                "索引名应稳定可识别");
        assertTrue(lower.contains("(group_id, card_name, deleted, sort, amount, id)"),
                "索引列顺序应匹配聚合查询");
    }
}
