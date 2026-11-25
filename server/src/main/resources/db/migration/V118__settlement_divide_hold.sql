-- =============================================================
-- V118：分账售后期冻结
--
-- 背景：订单核销后立即分账（钱进经营者子商户账户）会带来一个资金风险：
-- 用户之后申请售后退款时，钱可能已被经营者提现到银行卡，
-- divide/back 归还必然失败，只能平台垫付。
--
-- 解法：核销后先冻结 N 天（售后期），期间钱留在平台待分账账户。
-- 售后期内退款 → 钱还在平台，直接退款即可，无需追讨。
-- 售后期过后才真正分账，此时退款走 divide/back（残留风险由押金兜底）。
--
-- 配置：app.divide.hold-days（默认 7 天，设为 0 关闭冻结恢复「核销即分账」）
-- =============================================================

-- 冻结到期时间：核销时写入 now + hold-days；NULL 表示无需冻结
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'divide_hold_until');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN divide_hold_until DATETIME NULL COMMENT ''分账冻结到期时间（售后期结束才可分账）；NULL 表示无需冻结''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 到期扫描索引：DivideRetryJob 按 (divide_status, divide_hold_until) 找到期记录
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND INDEX_NAME = 'idx_settlement_hold');
SET @sql := IF(@idx = 0,
  'ALTER TABLE settlement_record ADD INDEX idx_settlement_hold (divide_status, divide_hold_until)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
