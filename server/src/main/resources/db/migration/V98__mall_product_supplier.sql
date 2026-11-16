-- V98: 商城商品统一面向全部门店销售，供应商提升为 SPU 级。
--
-- 迁移安全：
--   1. 若同一商品的 SKU 存在多个供应商，先让迁移失败，避免静默改变财务归属；
--   2. 单一供应商商品自动回填 mall_product.supplier_subject_id；
--   3. 旧门店/渠道/分账字段保留为历史兼容列，仅将门店列改为可空。

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_product'
       AND COLUMN_NAME = 'supplier_subject_id') = 0,
    'ALTER TABLE mall_product ADD COLUMN supplier_subject_id BIGINT UNSIGNED NULL COMMENT ''SPU 唯一供应商主体ID'' AFTER description',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_product'
       AND INDEX_NAME = 'idx_mall_product_supplier') = 0,
    'ALTER TABLE mall_product ADD KEY idx_mall_product_supplier (supplier_subject_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 有冲突时第二行因 CHECK 失败而终止迁移；-1 哨兵保证无冲突时表可通过检查。
DROP TEMPORARY TABLE IF EXISTS mall_supplier_consistency_guard;
CREATE TEMPORARY TABLE mall_supplier_consistency_guard (
    product_id BIGINT NOT NULL PRIMARY KEY,
    CONSTRAINT chk_mall_single_supplier CHECK (product_id < 0)
);
INSERT INTO mall_supplier_consistency_guard (product_id) VALUES (-1);
INSERT INTO mall_supplier_consistency_guard (product_id)
SELECT product_id
FROM mall_sku
WHERE deleted = 0 AND supplier_subject_id IS NOT NULL
GROUP BY product_id
HAVING COUNT(DISTINCT supplier_subject_id) > 1;
DROP TEMPORARY TABLE mall_supplier_consistency_guard;

UPDATE mall_product product
JOIN (
    SELECT product_id, MIN(supplier_subject_id) AS supplier_subject_id
    FROM mall_sku
    WHERE deleted = 0 AND supplier_subject_id IS NOT NULL
    GROUP BY product_id
) sku_supplier ON sku_supplier.product_id = product.id
SET product.supplier_subject_id = sku_supplier.supplier_subject_id
WHERE product.supplier_subject_id IS NULL AND product.deleted = 0;

ALTER TABLE mall_product
    MODIFY COLUMN attribution_store_subject_id BIGINT UNSIGNED NULL COMMENT '历史归属门店（已停用）';

ALTER TABLE orders
    MODIFY COLUMN store_subject_id BIGINT UNSIGNED NULL COMMENT '订单门店主体；商城订单为空';

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
       AND COLUMN_NAME = 'leader_subject_id') = 0,
    'ALTER TABLE orders ADD COLUMN leader_subject_id BIGINT UNSIGNED NULL COMMENT ''商城团长主体快照'' AFTER channel_subject_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
       AND COLUMN_NAME = 'leader_user_id') = 0,
    'ALTER TABLE orders ADD COLUMN leader_user_id BIGINT UNSIGNED NULL COMMENT ''商城团长用户快照'' AFTER leader_subject_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
