package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MallProductSupplierMigrationV98Test {

    private final String sql = readMigration();

    @Test
    void migrationMovesSupplierToSpuAndGuardsConflictingSkuSuppliers() throws Exception {
        String lower = sql.toLowerCase();
        assertTrue(lower.contains("add column supplier_subject_id"),
                "V98 必须为 mall_product 增加 SPU 供应商");
        assertTrue(lower.contains("count(distinct supplier_subject_id)"),
                "迁移必须检测同一商品存在多个 SKU 供应商");
        assertTrue(lower.contains("check (product_id < 0)"),
                "供应商冲突必须使迁移失败，不能静默选择");
        assertTrue(lower.contains("update mall_product"),
                "单一供应商必须从 SKU 回填到 SPU");
    }

    @Test
    void migrationAllowsGlobalMallOrdersWithoutStoreAndKeepsLeaderSnapshot() throws Exception {
        String lower = sql.toLowerCase();
        assertTrue(lower.contains("modify column attribution_store_subject_id"),
                "商城商品归属门店字段必须改为可空");
        assertTrue(lower.contains("modify column store_subject_id"),
                "商城订单必须允许无门店归属");
        assertTrue(lower.contains("add column leader_subject_id"),
                "商城订单必须保存团长主体快照");
        assertTrue(lower.contains("add column leader_user_id"),
                "商城订单必须保存团长用户快照");
    }

    private String readMigration() {
        try {
            return Files.readString(Path.of(
                    "src/main/resources/db/migration/V98__mall_product_supplier.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("V98 迁移文件不存在", e);
        }
    }
}
