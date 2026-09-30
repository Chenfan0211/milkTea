package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V71 活动城市（运营白名单）迁移守卫。 */
class ActivityCityMigrationV71Test {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V71__activity_city.sql");

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V71 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void shouldCreateActivityCityTable() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("create table if not exists activity_city"), "应创建 activity_city 表");
        assertTrue(lower.contains("region_id"), "应有 region_id 列");
        assertTrue(lower.contains("city_code"), "应有 city_code 列");
        assertTrue(lower.contains("city_name"), "应有 city_name 列");
        assertTrue(lower.contains("province_id"), "应有 province_id 列");
        assertTrue(lower.contains("status"), "应有 status 列");
        assertTrue(lower.contains("deleted"), "应有 deleted 列");
        assertFalse(lower.contains("flyway_schema_history"), "不得手工伪造 Flyway 历史");
    }

    @Test
    void shouldBackfillExistingStoreCitiesAsActivityCities() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("insert into activity_city"), "应回填活动城市");
        assertTrue(lower.contains("from region"), "应从 region 反查");
        assertTrue(lower.contains("store_profile"), "应按已有门店城市回填");
        assertTrue(lower.contains("distinct sp.city_id"), "应去重门店城市");
        assertTrue(lower.contains("level = 2"), "只回填市级");
    }

    @Test
    void shouldAddActivityCityIdColumnAndBackfill() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("add column activity_city_id"), "应给 store_profile 加 activity_city_id");
        assertTrue(lower.contains("join activity_city"), "应按 activity_city 回填门店绑定");
        assertTrue(lower.contains("idx_store_profile_activity_city"), "应建门店活动城市索引");
    }
}
