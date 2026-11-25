package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductSortMigrationV121Test {

    private final String sql = readMigration();

    @Test
    void migrationAddsProductSortColumnAndIndex() throws Exception {
        String lower = sql.toLowerCase();
        assertTrue(lower.contains("table_name = 'product'"), "V121 必须处理门店点单商品 product 表");
        assertTrue(lower.contains("add column sort int not null default 0"),
                "product 必须新增非空 sort 列并默认 0");
        assertTrue(lower.contains("idx_product_on_sale_sort"),
                "点单菜单按 on_sale + sort 读取，必须有组合索引");
        assertTrue(lower.contains("information_schema.columns") && lower.contains("information_schema.statistics"),
                "DDL 必须用 information_schema 守卫保证可重复执行");
    }

    @Test
    void migrationAddsMallProductStatusSortIndex() throws Exception {
        String lower = sql.toLowerCase();
        assertTrue(lower.contains("table_name = 'mall_product'"),
                "V121 必须补充商城商品的排序索引");
        assertTrue(lower.contains("idx_mall_product_status_sort"),
                "商城「全部」列表按 status + sort 过滤排序，必须有组合索引");
    }

    private String readMigration() {
        try {
            return Files.readString(Path.of(
                    "src/main/resources/db/migration/V121__product_sort_and_mall_product_sort.sql"));
        } catch (Exception e) {
            throw new IllegalStateException("V121 迁移文件不存在", e);
        }
    }
}
