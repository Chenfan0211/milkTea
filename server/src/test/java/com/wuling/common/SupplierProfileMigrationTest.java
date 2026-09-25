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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V37 供应商档案字段迁移守卫。
 *
 * <p><b>为什么需要它</b>：2026-09-25 需求「供应商需要增加一些基础信息」。
 * 运营后台「供应商管理」页此前只能维护编码/名称/状态，
 * {@code supplier_profile} 表仅有 product_count + status，没有任何联系信息。
 *
 * <p>本测试保证 V37：
 * <ol>
 *   <li>补齐约定的 5 个联系方式字段；</li>
 *   <li>每个 ADD COLUMN 都有 information_schema 幂等保护
 *       —— V31 曾因手工伪造 checksum 导致线上服务无法启动，之后约定迁移必须幂等且
 *       由 Flyway 自行执行；</li>
 *   <li>不包含人工插入 Flyway 历史表的危险写法；</li>
 *   <li>回填存量数据的 phone，避免编辑时因必填校验无法保存。</li>
 * </ol>
 */
class SupplierProfileMigrationTest {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V37__supplier_profile_contact_fields.sql");

    /** 本次约定的联系方式组字段（列名） */
    private static final Set<String> EXPECTED_COLUMNS = new LinkedHashSet<>(List.of(
            "contact_name", "phone", "address", "email", "remark"
    ));

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V37 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }

    /**
     * 去掉 SQL 行注释后再统计。
     *
     * <p><b>为什么必须先去注释</b>：脚本的说明注释里也会出现 "ADD COLUMN" 字样
     * （如「逐个字段用 information_schema 判断后再 ADD COLUMN」），
     * 不剥离就会把注释算成一条真实语句，导致计数虚高、测试误报。
     * 这类「正则匹配到注释」的坑本项目已踩过多次，故统一先剥离再统计。
     */
    private String sqlWithoutComments() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : sql().split("\\R")) {
            int idx = line.indexOf("--");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append(System.lineSeparator());
        }
        return sb.toString();
    }

    @Test
    void shouldAddAllContactColumns() throws IOException {
        String src = sqlWithoutComments();
        Set<String> addedCols = new LinkedHashSet<>();
        // 抓取 ALTER TABLE supplier_profile ADD COLUMN xxx
        Matcher m = Pattern.compile(
                "ADD\\s+COLUMN\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(src);
        while (m.find()) {
            addedCols.add(m.group(1).toLowerCase());
        }
        Set<String> missing = new LinkedHashSet<>(EXPECTED_COLUMNS);
        missing.removeAll(addedCols);
        assertTrue(missing.isEmpty(),
                "V37 未补齐以下字段: " + missing + "（实际新增: " + addedCols + "）");
    }

    /**
     * 每个字段的 ADD COLUMN 都必须先在 information_schema 里判断存在性。
     *
     * <p>计数方式：information_schema 查询次数应 ≥ 新增列数。
     * 若有人后续加字段却漏了幂等包装，重复部署会因 Duplicate column 报错并让服务起不来。
     */
    @Test
    void everyAddColumnMustBeIdempotentGuarded() throws IOException {
        String src = sqlWithoutComments();
        int addColCount = 0;
        for (Matcher m = Pattern.compile("ADD\\s+COLUMN", Pattern.CASE_INSENSITIVE).matcher(src); m.find(); ) {
            addColCount++;
        }
        int guardCount = 0;
        for (Matcher m = Pattern.compile("information_schema\\.columns", Pattern.CASE_INSENSITIVE).matcher(src); m.find(); ) {
            guardCount++;
        }
        assertTrue(addColCount > 0, "V37 应至少新增一个列");
        assertTrue(guardCount >= addColCount,
                "存在未做幂等保护的新增列：ADD COLUMN=" + addColCount
                        + " 但 information_schema 判断仅 " + guardCount + " 次。"
                        + "每个新增列都必须先用 information_schema 判断，否则重复执行会因 Duplicate column 失败。");
    }

    @Test
    void shouldBackfillPhoneForExistingRows() throws IOException {
        String src = sqlWithoutComments();
        assertTrue(src.contains("UPDATE supplier_profile"),
                "缺少存量数据回填：phone 为必填，不回填会导致存量供应商无法保存编辑");
        assertTrue(src.contains("phone IS NULL") || src.contains("phone IS null"),
                "回填应只针对 phone 为空的行（幂等，不覆盖已有号码）");
    }

    @Test
    void shouldAvoidDangerousPatterns() throws IOException {
        String lower = sqlWithoutComments().toLowerCase();
        // 严禁手工伪造 Flyway 历史（V31 踩过的坑）
        assertFalse(lower.contains("flyway_schema_history"),
                "严禁人工写历史表 —— 迁移必须由 Flyway 自行执行并记录 checksum");
        assertFalse(lower.contains("drop table"), "V37 不应包含 DROP TABLE");
        // 回填/更新必须限定 deleted = 0
        assertTrue(lower.contains("where deleted = 0") || lower.contains("and deleted = 0"),
                "回填 UPDATE 必须限定 deleted = 0");
    }

    @Test
    void migrationVersionShouldBeV37() {
        assertTrue(MIGRATION.getFileName().toString().startsWith("V37"),
                "版本号需唯一，避免与既有迁移冲突");
    }
}