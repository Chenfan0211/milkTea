-- =============================================================
-- V119：易宝收款所需的三个字段
--
-- 背景：接入易宝收款后，需要区分「钱来自哪个渠道」并支持退款归集。
-- 三个字段各有明确用途，缺一个都会让某个环节做不成：
--
--   fund_channel              区分微信池/易宝池，渠道可提现额与对账依赖它
--   yeepay_divide_detail_no   退款归集的必需参数（易宝要求传分账明细单号）
--   clearing_status           分账前置门禁（易宝要求清算成功后才能分账）
--
-- 幂等：information_schema 判断后再执行。
-- =============================================================

-- ---------- 1. 资金来源渠道 ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'fund_channel');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN fund_channel VARCHAR(16) NULL COMMENT ''资金来源渠道 WXPAY/YEEPAY；核销时从订单支付渠道写入''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 易宝分账明细单号 ----------
-- 为什么必须单独存：divide/apply 的响应是「批次 + 明细数组」结构，
-- 只有批次级 unique_divide_no 无法定位到某个接收方。
-- 退款归集要求传「易宝分账明细订单号」，即明细级的 divideDetailNo。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'yeepay_divide_detail_no');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN yeepay_divide_detail_no VARCHAR(64) NULL COMMENT ''易宝分账明细单号(divideDetailNo)，退款归集必需''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. 清算状态 ----------
-- 易宝要求「清算成功后才能分账/退款」，核销可能早于清算，
-- 故需要此列做门禁，避免直接调 divide/apply 撞 OPR21016。
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND COLUMN_NAME = 'clearing_status');
SET @sql := IF(@col = 0,
  'ALTER TABLE settlement_record ADD COLUMN clearing_status VARCHAR(16) NULL COMMENT ''清算状态 PENDING 待清算 / SUCCESS 已清算''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. 回填历史数据 ----------

-- 渠道：按 order_no 关联 payment.channel
UPDATE settlement_record sr
JOIN payment p ON p.order_no = sr.order_no AND p.deleted = 0
SET sr.fund_channel = UPPER(p.channel)
WHERE sr.fund_channel IS NULL;

-- 回填不到的保守标 WXPAY：易宝收款是本项目新增能力，历史订单都是微信
UPDATE settlement_record SET fund_channel = 'WXPAY' WHERE fund_channel IS NULL;

-- 历史记录视为已清算：微信渠道无清算概念，且资金都已入账
UPDATE settlement_record SET clearing_status = 'SUCCESS' WHERE clearing_status IS NULL;

-- ---------- 5. 索引 ----------

-- 渠道可提现额查询：按 (subject, channel, divide_status) 扫描
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND INDEX_NAME = 'idx_settlement_channel');
SET @sql := IF(@idx = 0,
  'ALTER TABLE settlement_record ADD INDEX idx_settlement_channel (subject_id, fund_channel, divide_status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 清算门禁扫描：找待清算的分账记录
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'settlement_record'
               AND INDEX_NAME = 'idx_settlement_clearing');
SET @sql := IF(@idx = 0,
  'ALTER TABLE settlement_record ADD INDEX idx_settlement_clearing (clearing_status, divide_status)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
