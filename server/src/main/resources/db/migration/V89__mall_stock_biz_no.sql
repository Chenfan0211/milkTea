-- =====================================================================
-- V89: 商城库存业务幂等键
--
-- 同一订单可能发生多次部分售后；订单号不能继续充当每次回补的唯一幂等键。
-- biz_type + biz_no 组成业务幂等键：普通订单为 ORDER/orderNo，售后为 AFTER_SALE/afterSaleNo。
-- =====================================================================
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_stock_log'
       AND COLUMN_NAME = 'biz_type') = 0,
    'ALTER TABLE mall_stock_log ADD COLUMN biz_type VARCHAR(16) NULL COMMENT ''业务类型：ORDER/AFTER_SALE'' AFTER order_no',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_stock_log'
       AND COLUMN_NAME = 'biz_no') = 0,
    'ALTER TABLE mall_stock_log ADD COLUMN biz_no VARCHAR(64) NULL COMMENT ''业务幂等号：订单号/售后单号'' AFTER biz_type',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mall_stock_log SET biz_type = 'ORDER', biz_no = order_no
 WHERE (biz_type IS NULL OR biz_no IS NULL) AND order_no IS NOT NULL;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_stock_log'
                 AND INDEX_NAME = 'uk_mall_stock_log_biz_change_sku');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE mall_stock_log ADD UNIQUE KEY uk_mall_stock_log_biz_change_sku (biz_type, biz_no, change_type, sku_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
