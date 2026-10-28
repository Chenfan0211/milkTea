package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MallCategoryIconMigrationV94Test {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V94__mall_category_icon.sql");

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V94 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void addsNullableIconImageToMallCategory() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("alter table `mall_category`"), "必须修改 mall_category 表");
        assertTrue(lower.contains("icon_image"), "必须新增 icon_image 字段");
        assertTrue(lower.contains("varchar(255)"), "icon_image 必须可保存文件服务 URL");
        assertTrue(lower.contains("null"), "存量分类不允许因新增字段失败");
        assertTrue(lower.contains("分类图标url"), "字段必须有中文注释");
    }

    @Test
    void isIdempotentAndDoesNotForgeFlywayHistory() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("information_schema.columns"), "新增字段前必须检查是否已存在");
        assertTrue(lower.contains("prepare stmt from @add_icon_image"), "DDL 必须通过 PREPARE 幂等执行");
        assertFalse(lower.contains("flyway_schema_history"), "严禁手工写 flyway_schema_history");
    }
}
