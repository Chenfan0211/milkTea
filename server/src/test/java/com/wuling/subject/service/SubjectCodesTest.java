package com.wuling.subject.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主体编码规则回归。
 *
 * <p><b>背景（线上现象）</b>：新增门店/渠道/供应商/投资人时报 500「服务器内部错误」，
 * 日志里是 {@code Duplicate entry 'ST-1005' for key 'uk_biz_subject_code'}。
 * 根因是三套「按行数推算序号」的编码生成器，详见 {@link SubjectCodes} 的类注释。
 *
 * <p>本测试只锁编码规则本身（纯函数，不碰数据库）；跨表场景
 * （软删除后再新增、手工编码查重）由 {@link SubjectCodeCollisionTest} 用真 SQL 覆盖。
 */
class SubjectCodesTest {

    @Test
    @DisplayName("编码 = 前缀 + 8 位随机数字 + 原始自增主键")
    void codeIsPrefixPlusRandomNumberAndId() {
        assertGeneratedCode("STORE", "ST", 106);
        assertGeneratedCode("CHANNEL", "RS", 31);
        assertGeneratedCode("SUPPLIER", "SU", 32);
        assertGeneratedCode("INVESTOR", "IV", 33);
        assertGeneratedCode("PLATFORM", "PT", 1);
        assertGeneratedCode("LEADER", "LD", 20);
        // 未知类型收敛到 SUB，不会与任何已知前缀撞
        assertGeneratedCode("WHATEVER", "SUB", 99);
        assertGeneratedCode(null, "SUB", 99);
    }

    @Test
    @DisplayName("类型大小写不影响前缀")
    void typeIsCaseInsensitive() {
        assertGeneratedCode("store", "ST", 7);
        assertGeneratedCode("Channel", "RS", 7);
    }

    @Test
    @DisplayName("同类型内不同主键必得不同编码（这是不撞号的根据）")
    void distinctIdsYieldDistinctCodes() {
        for (String type : new String[]{"STORE", "CHANNEL", "SUPPLIER", "INVESTOR", "PLATFORM", "LEADER"}) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (long id = 1; id <= 5000; id++) {
                assertTrue(seen.add(SubjectCodes.of(type, id)),
                        "类型 " + type + " 的主键 " + id + " 生成了重复编码");
            }
        }
    }

    @Test
    @DisplayName("随机数加主键编码永远不会等于 4 位历史种子码")
    void generatedCodeCannotCollideWithLegacySeeds() {
        // 历史种子：ST-1001..ST-1005、RS-1000..1002、SU-1000..1002、IV-1000..1002、PT-1000。
        // 新编码至少包含 8 位随机数字和主键，位数与历史 4 位种子码不同。
        assertNotEquals("ST-1001", SubjectCodes.of("STORE", 1001));
        assertGeneratedCode("STORE", "ST", 1001);
        assertNotEquals("RS-1000", SubjectCodes.of("CHANNEL", 1000));
        assertGeneratedCode("CHANNEL", "RS", 1000);
        assertNotEquals("IV-1002", SubjectCodes.of("INVESTOR", 1002));
        assertGeneratedCode("INVESTOR", "IV", 1002);
        assertNotEquals("PT-1000", SubjectCodes.of("PLATFORM", 1000));
        assertGeneratedCode("PLATFORM", "PT", 1000);
    }

    @Test
    @DisplayName("不同类型同主键不撞（全局唯一键安全）")
    void differentTypesSameIdDoNotCollide() {
        assertNotEquals(SubjectCodes.of("STORE", 5), SubjectCodes.of("CHANNEL", 5));
        assertNotEquals(SubjectCodes.of("SUPPLIER", 5), SubjectCodes.of("INVESTOR", 5));
    }

    @Test
    @DisplayName("占位码带随机 UUID，并发插入之间不冲突")
    void placeholderIsUniquePerCall() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = SubjectCodes.placeholder("STORE");
            assertTrue(seen.add(code), "占位码出现了重复：" + code);
            assertTrue(code.startsWith("ST-TMP-"), "占位码应带 -TMP- 便于事后排查：" + code);
            assertTrue(SubjectCodes.isPlaceholder(code));
        }
    }

    @Test
    @DisplayName("正式编码不会被误判为占位码")
    void formalCodeIsNotPlaceholder() {
        for (long id = 1; id <= 100; id++) {
            assertFalse(SubjectCodes.isPlaceholder(SubjectCodes.of("STORE", id)));
        }
        assertFalse(SubjectCodes.isPlaceholder(null));
        assertFalse(SubjectCodes.isPlaceholder("ST-1001"));
    }

    @Test
    @DisplayName("主键非法时立即失败，不产出不可用编码")
    void nonPositiveIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SubjectCodes.of("STORE", 0));
        assertThrows(IllegalArgumentException.class, () -> SubjectCodes.of("STORE", -1));
    }

    private static void assertGeneratedCode(String subjectType, String expectedPrefix, long id) {
        String code = SubjectCodes.of(subjectType, id);
        assertTrue(
                code.matches(Pattern.quote(expectedPrefix) + "-\\d{8}" + Pattern.quote(String.valueOf(id))),
                "编码应符合 " + expectedPrefix + "-8位随机数" + id + "，实际：" + code
        );
    }
}
