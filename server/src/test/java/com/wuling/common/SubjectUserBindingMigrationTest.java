package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V68「用户 ↔ 主体 绑定关系一致性」迁移守卫。
 *
 * <p><b>背景（真实数据不一致）</b>：{@code app_user.bound_subject_id} 与
 * {@code biz_subject.bound_user_id} 是同一关系的两个方向，由
 * {@code /admin/subject/binding} 双向写入。历史写入缺少一致性校验与旧值清理，
 * 导致「门店管理页显示的用户」与「用户列表显示的绑定主体」对不上。
 *
 * <p>本测试保证 V68 迁移：
 * <ol>
 *   <li>先修数据、再加唯一索引（顺序不能反，否则脏数据导致建索引失败）；</li>
 *   <li>以 biz_subject.bound_user_id 为准重建（用户决策口径）；</li>
 *   <li>处理「一个用户被多个主体指向」的情况；</li>
 *   <li>为两个方向各加 1:1 唯一索引；</li>
 *   <li>DDL 用 information_schema 幂等包装；严禁伪造 flyway 历史。</li>
 * </ol>
 */
class SubjectUserBindingMigrationTest {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V68__subject_user_binding_consistency.sql");

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V68 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    @Test
    void migrationFileShouldExistAndBeNonTrivial() throws IOException {
        String sql = sql();
        assertTrue(sql.length() > 500, "迁移脚本不应是空壳");
        assertTrue(sql.contains("bound_user_id"), "应提及 bound_user_id");
        assertTrue(sql.contains("bound_subject_id"), "应提及 bound_subject_id");
    }

    @Test
    void shouldRebuildFromBizSubjectSide() throws IOException {
        String sql = sql();
        // 口径：以 biz_subject.bound_user_id 为准回填 app_user.bound_subject_id
        assertTrue(sql.contains("JOIN biz_subject s ON s.bound_user_id = u.id"),
                "必须以 biz_subject.bound_user_id 为准重建 app_user.bound_subject_id");
        assertTrue(sql.contains("SET u.bound_subject_id = s.id"),
                "应把 app_user.bound_subject_id 重建为对应主体 id");
    }

    @Test
    void shouldDeduplicateMultiBinding() throws IOException {
        String lower = sql().toLowerCase();
        // 处理「一个用户被多个主体指向」：分组保留一条，其余解绑
        assertTrue(lower.contains("group by bound_user_id"),
                "应处理一个用户被多个主体指向的情况");
        assertTrue(lower.contains("having count(*) > 1"),
                "应仅对重复绑定（count > 1）做去重");
    }

    @Test
    void shouldAddUniqueIndexesOnBothDirections() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("uk_biz_subject_bound_user"),
                "biz_subject.bound_user_id 应加唯一索引（一个用户只能绑一个主体）");
        assertTrue(sql.contains("uk_app_user_bound_subject"),
                "app_user.bound_subject_id 应加唯一索引（一个主体只能绑一个用户）");
        assertTrue(sql.contains("UNIQUE KEY uk_biz_subject_bound_user (bound_user_id)"),
                "唯一索引应建立在 bound_user_id 列上");
        assertTrue(sql.contains("UNIQUE KEY uk_app_user_bound_subject (bound_subject_id)"),
                "唯一索引应建立在 bound_subject_id 列上");
    }

    @Test
    void shouldBeIdempotentAndAvoidDangerousPatterns() throws IOException {
        String lower = sql().toLowerCase();
        // DDL 必须用 information_schema 幂等包装（可重复执行）
        assertTrue(lower.contains("information_schema.statistics"),
                "加索引前必须判断索引是否已存在（幂等）");
        assertTrue(lower.contains("prepare stmt from @sql"),
                "DDL 应通过 PREPARE 执行幂等包装");
        // 严禁手工伪造 Flyway 历史（V31 踩过的坑）
        assertFalse(lower.contains("flyway_schema_history"),
                "严禁手工写 flyway_schema_history —— 必须让 Flyway 自行执行并记录 checksum");
        // 修复数据的 UPDATE 必须限定 deleted = 0
        assertTrue(lower.contains("where u.deleted = 0") || lower.contains("and u.deleted = 0"),
                "重建绑定必须限定 deleted = 0，避免改动已删除用户");
    }

    @Test
    void migrationVersionShouldBeV68() {
        assertTrue(MIGRATION.getFileName().toString().startsWith("V68__"),
                "版本号需唯一，避免与既有迁移冲突");
    }
}
