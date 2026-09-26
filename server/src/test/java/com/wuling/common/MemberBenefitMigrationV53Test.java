package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V53：会员权益字典与折扣百分比迁移守卫。 */
class MemberBenefitMigrationV53Test {

    private static final Path MIGRATION = Paths.get(
            "src/main/resources/db/migration/V53__member_benefit_dictionary_and_discount_percent.sql");

    private String normalizedSql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .toLowerCase()
                .replaceAll("\\s+", " ");
    }

    @Test
    void shouldSeedMemberBenefitDictionaryIdempotently() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("member_benefit"), "应创建会员权益字典类型");
        assertTrue(sql.contains("会员权益"), "字典名称应为会员权益");
        assertTrue(sql.contains("not exists"), "没有唯一索引时必须用 NOT EXISTS 保证幂等");
        assertFalse(sql.contains("on duplicate key"), "不得依赖不存在的字典项唯一索引");
        for (String item : new String[]{
                "基础折扣", "生日月双倍时光币", "专属会员价", "专属优惠券",
                "新品优先体验", "时光币1.5倍", "生日免费饮品"}) {
            assertTrue(sql.contains(item), "应初始化权益项: " + item);
        }
    }

    @Test
    void shouldConvertRecognizedDiscountFormatsToIntegerPercent() throws IOException {
        String sql = normalizedSql();

        assertTrue(sql.contains("update member_level"), "应迁移 member_level.discount");
        assertTrue(sql.contains("replace(trim(discount), '折', '')"), "应识别中文折扣");
        assertTrue(sql.contains("replace(trim(discount), '%', '')"), "应识别百分号折扣");
        assertTrue(sql.contains("* 10"), "N折应转换为百分比");
        assertTrue(sql.contains("* 100"), "0.x 比率应转换为百分比");
        assertTrue(sql.contains("round("), "转换结果应取整");
        assertTrue(sql.contains("cast("), "转换结果应保存为整数百分比字符串");
        assertTrue(sql.contains("where trim(discount) regexp"), "只迁移可识别格式，未知值保持不变");
    }

    @Test
    void shouldNeverTouchFlywayHistory() throws IOException {
        String sql = normalizedSql();
        assertFalse(sql.contains("flyway_schema_history"), "严禁手工写 Flyway 历史");
    }
}