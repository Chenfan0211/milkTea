-- =============================================================
-- V113：settlement_record 增加易宝分账追踪字段
--
-- 背景：订单分账路线（divide/apply）是「逐订单」发起的。
-- 必须记录每笔结算记录的易宝分账进度，否则：
--   1. 审核重试 / 任务重跑会重复分账（真实资损）；
--   2. 渠道可用额算不准（不知道哪些钱已真正分到子商户）。
--
-- divide_status 同时承载「未入驻」状态：收款方尚未入驻时，
-- 钱留在平台待分账账户，标记 PENDING_ONBOARD，待入驻完成后补分账。
--
-- 幂等：用 information_schema 判断后再执行，可重复运行。
-- =============================================================

-- 订单号冗余列：渠道可用额与对账要按 order_no 关联 payment.channel
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'order_no');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN order_no VARCHAR(64) NULL COMMENT ''订单号（冗余，便于按渠道对账）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 用 order_id 回填历史数据的 order_no
UPDATE settlement_record sr
JOIN split_snapshot ss ON ss.id = sr.snapshot_id
SET sr.order_no = ss.order_no
WHERE sr.order_no IS NULL AND ss.order_no IS NOT NULL;

-- 分账进度
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'divide_status');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN divide_status VARCHAR(32) NULL COMMENT ''NONE/PENDING_ONBOARD/PROCESSING/SUCCESS/FAIL/HOLD''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 已分出金额（分）：支持部分分账与重复校验
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'divided_amount');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN divided_amount BIGINT NOT NULL DEFAULT 0 COMMENT ''已分出金额（分）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 分账请求号：商户侧唯一，易宝幂等键
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'divide_request_id');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN divide_request_id VARCHAR(64) NULL COMMENT ''易宝分账请求号（幂等键）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 易宝分账流水号
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'unique_divide_no');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN unique_divide_no VARCHAR(64) NULL COMMENT ''易宝分账流水号''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 分账完成时间
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'divide_time');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN divide_time DATETIME NULL COMMENT ''分账完成时间''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 分账扫描索引：按主体找待分账记录
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND INDEX_NAME = 'idx_settlement_divide');
SET @sql := IF(@idx = 0,
  'ALTER TABLE settlement_record ADD INDEX idx_settlement_divide (subject_id, divide_status, settle_date)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 分账请求号全局唯一（除 NULL 外），防重复分账
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND INDEX_NAME = 'uk_settlement_divide_request');
SET @sql := IF(@idx = 0,
  'ALTER TABLE settlement_record ADD UNIQUE KEY uk_settlement_divide_request (divide_request_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据：已结算的记录默认视为「未分账」（微信渠道不需要分账，留 NONE 即可）
UPDATE settlement_record SET divide_status = 'NONE' WHERE divide_status IS NULL;
