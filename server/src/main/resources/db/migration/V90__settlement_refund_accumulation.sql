-- =====================================================================
-- V90: 结算记录累计退款冲正
--
-- 同一订单可发生多次部分退款。每次冲正必须基于分账原金额与累计已冲正金额，
-- 不能拿扣减后的剩余金额再乘比例，否则第二次及之后会少扣。
-- =====================================================================

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
       AND COLUMN_NAME = 'original_amount') = 0,
    'ALTER TABLE settlement_record ADD COLUMN original_amount BIGINT NULL COMMENT ''分账原金额(分)，用于累计退款冲正'' AFTER amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
       AND COLUMN_NAME = 'refunded_amount') = 0,
    'ALTER TABLE settlement_record ADD COLUMN refunded_amount BIGINT NULL DEFAULT 0 COMMENT ''累计已冲正金额(分)'' AFTER original_amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE settlement_record
SET original_amount = amount
WHERE original_amount IS NULL;

UPDATE settlement_record
SET refunded_amount = 0
WHERE refunded_amount IS NULL;
