package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V49 礼品卡分组关联字段扩宽守卫。 */
class GiftCardAdminMigrationV49Test {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V49__widen_gift_card_face_metadata.sql");

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V49 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    @Test
    void shouldWidenGroupIdToMatchGroupCode() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("alter table gift_card_denomination"), "应修改礼品卡面额表");
        assertTrue(sql.contains("modify column group_id varchar(64)"), "group_id 应扩宽到 64 以承载分组 code");
        assertFalse(sql.contains("flyway_schema_history"), "不得手工伪造 Flyway 历史");
    }

    @Test
    void shouldWidenGroupTitleToMatchGroupName() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("modify column group_title varchar(128)"), "group_title 应扩宽到 128 以承载分组名称");
    }
}
