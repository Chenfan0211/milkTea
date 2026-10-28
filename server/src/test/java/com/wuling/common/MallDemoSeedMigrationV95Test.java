package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MallDemoSeedMigrationV95Test {

    private static final Path MIGRATION =
            Paths.get("src/main/resources/db/migration/V95__mall_demo_seed.sql");

    private String sql() throws IOException {
        assertTrue(Files.exists(MIGRATION), "V95 迁移脚本应存在: " + MIGRATION);
        return Files.readString(MIGRATION, StandardCharsets.UTF_8);
    }
    @Test
    void seedsThreeCategoriesAndEightDemoProducts() throws IOException {
        String sql = sql();
        for (String code : new String[]{"tea-goods", "living-goods", "gift-box"}) {
            assertTrue(sql.contains("'" + code + "'"), "缺少演示分类: " + code);
        }
        for (int number = 1; number <= 8; number++) {
            String productCode = String.format("DEMO-MALL-%03d", number);
            String skuCode = String.format("DEMO-MALL-SKU-%03d", number);
            assertTrue(sql.contains(productCode), "缺少演示商品: " + productCode);
            assertTrue(sql.contains(skuCode), "缺少演示 SKU: " + skuCode);
        }
    }
    @Test
    void coversOnSaleOriginalPriceAndSoldOutData() throws IOException {
        String sql = sql().toUpperCase();
        assertTrue(sql.contains("'ON_SALE'"), "演示数据必须包含在售商品");
        assertTrue(sql.contains("ORIGINAL_PRICE"), "演示数据必须包含划线价字段");
        assertTrue(sql.contains("16900 AS ORIGINAL_PRICE"), "演示数据必须包含划线价商品");
        assertTrue(sql.contains("UNION ALL SELECT 'DEMO-MALL-008', 'DEMO-MALL-SKU-008', 3900, 0, 1800, 200, 0"), "演示数据必须包含售罄库存商品");
    }
    @Test
    void resolvesDependenciesByExistingCodes() throws IOException {
        String sql = sql();
        assertTrue(sql.contains("SELECT id FROM biz_subject"), "主体必须按编码动态解析");
        assertTrue(sql.contains("WHERE code = 'ST-1001'"), "门店必须复用现有种子 ST-1001");
        assertTrue(sql.contains("WHERE code = 'SU-1000'"), "供应商必须复用现有种子 SU-1000");
        assertTrue(sql.contains("WHERE code = 'SR-1000'"), "分账规则必须复用现有全局规则 SR-1000");
        assertTrue(sql.contains("JOIN mall_category category ON category.code"), "商品分类必须按编码关联");
        assertTrue(sql.contains("JOIN mall_product product ON product.product_code"), "SKU 必须按商品编码关联");
    }
    @Test
    void allDemoRowsAreIdempotent() throws IOException {
        String sql = sql();
        String executableSql = sql.replaceAll("(?m)^\\s*--.*$", "");
        assertEquals(3, executableSql.split("(?i)ON DUPLICATE KEY UPDATE", -1).length - 1, "三组种子写入都必须幂等");
        assertFalse(sql.toLowerCase().contains("flyway_schema_history"), "严禁手工写 flyway_schema_history");
        assertTrue(sql.contains("deleted = 0"), "幂等更新必须恢复演示数据");
    }
    @Test
    void requiresV94IconColumnBeforeSeeding() throws IOException {
        String sql = sql();
        String lower = sql.toLowerCase();
        assertTrue(lower.contains("icon_image"), "V95 必须依赖 V94 的 icon_image 列");
        assertTrue(lower.contains("information_schema.columns"), "V95 必须显式校验 V94 已执行");
        assertTrue(lower.contains("v94__mall_category_icon.sql"), "依赖缺失时必须提示先执行 V94");
    }
    @Test
    void failsFastWhenRequiredSeedDependenciesAreMissing() throws IOException {
        String lower = sql().toLowerCase();
        assertTrue(lower.contains("@mall_store_subject_id is null"), "门店主体缺失时必须终止");
        assertTrue(lower.contains("@mall_supplier_subject_id is null"), "供应商主体缺失时必须终止");
        assertTrue(lower.contains("@mall_split_rule_id is null"), "分账规则缺失时必须终止");
        assertTrue(lower.contains("signal sqlstate"), "依赖缺失必须显式报错，禁止静默写入 NULL");
        assertFalse(lower.contains("flyway_schema_history"), "严禁手工写 flyway_schema_history");
}

}
