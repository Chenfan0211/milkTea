-- V121: 商品运营排序号 + 两端列表排序索引。
--
-- 背景：
--   1. 门店点单商品 product 表没有排序号，后台列表与小程序菜单都只能按 id 排，
--      运营无法干预点单页的商品顺序；
--   2. 商城商品 mall_product 虽有 sort 列，但缺「status + sort」组合索引，
--      小程序「全部」列表（不带 categoryId）只能走 category_id 前缀索引后再 filesort。
--
-- 约定：sort 越小越靠前，取值 0-9999，默认 0（全为 0 时顺序与升级前完全一致）。
--
-- 幂等：DDL 用 information_schema 守卫，可重复执行。

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product'
       AND COLUMN_NAME = 'sort') = 0,
    'ALTER TABLE product ADD COLUMN sort INT NOT NULL DEFAULT 0 COMMENT ''运营排序号，越小越靠前'' AFTER on_sale',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product'
       AND INDEX_NAME = 'idx_product_on_sale_sort') = 0,
    'ALTER TABLE product ADD KEY idx_product_on_sale_sort (on_sale, sort)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_product'
       AND INDEX_NAME = 'idx_mall_product_status_sort') = 0,
    'ALTER TABLE mall_product ADD KEY idx_mall_product_status_sort (status, sort)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
