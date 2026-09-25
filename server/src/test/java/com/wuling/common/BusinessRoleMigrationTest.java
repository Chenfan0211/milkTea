package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V36 经营角色归一化迁移守卫。
 *
 * <p><b>为什么需要它</b>：2026-09-25 用户反馈「解绑了还显示数据 + 经营角色显示英文 STORE」。
 * 根因之一是 {@code app_user.business_role} 存在三种命名混存
 * （大写 {@code STORE} / 小写 {@code store} / 中文「门店」），
 * 前端映射表用小写 key，大写值不命中就原样打印英文。
 *
 * <p>本测试保证 V36 迁移：
 * <ol>
 *   <li>覆盖所有已知的角色命名变体（含中文），不漏映射；</li>
 *   <li>归并目标值限定在权威枚举 {@code STORE/INVESTOR/CHANNEL} 内
 *       （与 {@code SubjectBindingController#bindUserRole} 写入值一致）；</li>
 *   <li>至少包含步骤 2/3/4 三类一致性修复；</li>
 *   <li>不存在 DDL 与「手工写 flyway_schema_history」的危险写法
 *       —— V31 曾因手工伪造 checksum 导致线上服务无法启动。</li>
 * </ol>
 */
class BusinessRoleMigrationTest {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V36__unify_business_role_values.sql");

    /** 权威枚举（与后端 SubjectBindingController 写入值一致） */
    private static final Set<String> CANONICAL = Set.of("STORE", "INVESTOR", "CHANNEL");

    /** 必须被映射覆盖的历史命名变体 */
    private static final Set<String> LEGACY_VARIANTS = Set.of(
            "STORE", "store", "门店",
            "INVESTOR", "investor", "投资人",
            "CHANNEL", "channel", "RESOURCE", "resource", "资源方"
    );

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V36 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    /** 解析所有 CASE ... WHEN 'X' THEN 'Y' 映射对（大小写不敏感地读 WHEN 分支）。 */
    private static List<String> whenBranches(String sql) {
        List<String> branches = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("WHEN\\s+'([^']+)'\\s+THEN", Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) {
            branches.add(m.group(1));
        }
        return branches;
    }

    /** 解析所有 THEN 后的目标值。 */
    private static Set<String> thenTargets(String sql) {
        Set<String> targets = new LinkedHashSet<>();
        Matcher m = Pattern.compile("THEN\\s+'([^']+)'", Pattern.CASE_INSENSITIVE).matcher(sql);
        while (m.find()) {
            targets.add(m.group(1));
        }
        return targets;
    }

    @Test
    void migrationFileShouldExistAndBeNonTrivial() throws IOException {
        String sql = sql();
        assertTrue(sql.length() > 500, "迁移脚本不应是空壳");
        // 注释中应说明背景，便于后人追溯
        assertTrue(sql.contains("business_role"), "应提及 business_role");
    }

    @Test
    void shouldCoverAllLegacyRoleVariants() throws IOException {
        List<String> branches = whenBranches(sql());
        Set<String> upperBranches = new LinkedHashSet<>();
        branches.forEach(b -> upperBranches.add(b.toUpperCase()));

        Set<String> missing = new LinkedHashSet<>();
        for (String variant : LEGACY_VARIANTS) {
            // 迁移用 UPPER(TRIM(...)) 归一后比较，故英文变体只需大写形式出现在 WHEN 中
            String probe = variant.toUpperCase();
            if (!upperBranches.contains(probe)) {
                missing.add(variant);
            }
        }
        assertTrue(missing.isEmpty(), "以下角色命名变体未被 V36 覆盖: " + missing);
    }

    @Test
    void shouldOnlyMapToCanonicalEnumValues() throws IOException {
        Set<String> targets = thenTargets(sql());
        for (String t : targets) {
            assertTrue(CANONICAL.contains(t),
                    "V36 把角色归并到了非权威值 [" + t + "]，权威枚举应为 " + CANONICAL
                            + "（与 SubjectBindingController#bindUserRole 的 toUpperCase 写入值保持一致）");
        }
    }

    @Test
    void shouldIncludeAllThreeConsistencyFixes() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("SET business_role"), "缺少步骤1：归并 business_role 脏值");
        assertTrue(sql.contains("biz_subject.bound_user_id") || sql.contains("s.bound_user_id")
                        || sql.contains("SET s.bound_user_id"),
                "缺少步骤2：反向补全 biz_subject.bound_user_id（修复单向不一致）");
        assertTrue(sql.contains("u.bound_subject_id IS NOT NULL"), "缺少步骤3：补全角色为空的已绑定记录");
        assertTrue(sql.contains("s.id IS NULL"), "缺少步骤4：清理悬空绑定");
    }

    @Test
    void shouldBeIdempotentAndAvoidDangerousPatterns() throws IOException {
        String lower = sql().toLowerCase();
        // 纯 UPDATE，不应有 DDL —— DDL 需 information_schema 幂等包装，本脚本刻意不用
        assertFalse(lower.contains("alter table"), "V36 不应包含 DDL（本脚本只需数据归并）");
        assertFalse(lower.contains("create table"), "V36 不应包含 DDL");
        // 严禁手工伪造 Flyway 历史（V31 踩过的坑：checksum 不匹配导致服务无法启动）
        assertFalse(lower.contains("flyway_schema_history"),
                "严禁手工写 flyway_schema_history —— 必须让 Flyway 自行执行并记录 checksum");
        // 归并语句必须带 WHERE，防止误伤全表
        assertTrue(lower.contains("where deleted = 0"),
                "归并 UPDATE 必须限定 deleted = 0，避免改动已删除数据");
    }

    @Test
    void migrationVersionShouldBeV36() {
        assertEquals("V36__unify_business_role_values.sql", MIGRATION.getFileName().toString(),
                "版本号需唯一，避免与既有迁移冲突");
    }
}