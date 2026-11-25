-- =============================================================
-- V114：withdrawal 增加渠道路由与收款方快照字段
--
-- 背景：微信收款与易宝收款并存后，资金分散在两个资金池：
--   微信收的钱 → 微信商户号
--   易宝收的钱 → 经营者子商户待结算账户
-- 提现必须按资金来源路由到对应出款通道，否则会拿别处的钱垫付。
--
-- payee_snapshot：冻结时固化收款账户，之后经营者换卡不影响历史凭证。
-- 幂等：information_schema 判断后再执行。
-- =============================================================

-- 资金来源渠道
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'fund_channel');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN fund_channel VARCHAR(16) NULL COMMENT ''资金来源 WXPAY/YEEPAY''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 实际出款通道
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'payout_channel');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN payout_channel VARCHAR(16) NULL COMMENT ''出款通道 MOCK/WXPAY/YEEPAY''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 收款账户 id（subject_payout_account.id）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'payee_account_id');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN payee_account_id BIGINT UNSIGNED NULL COMMENT ''易宝结算卡记录 id''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 收款账户快照（JSON）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'payee_snapshot');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN payee_snapshot JSON NULL COMMENT ''收款账户快照（冻结时固化）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 易宝结算单号
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'yeepay_order_no');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN yeepay_order_no VARCHAR(64) NULL COMMENT ''易宝结算单号''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 易宝结算状态
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'yeepay_settle_status');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN yeepay_settle_status VARCHAR(32) NULL COMMENT ''易宝结算状态原始值''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史数据：此前所有提现都是微信链路
UPDATE withdrawal SET payout_channel = 'WXPAY' WHERE payout_channel IS NULL;
UPDATE withdrawal SET fund_channel = 'WXPAY' WHERE fund_channel IS NULL;

-- 补偿任务按状态+渠道扫描
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND INDEX_NAME = 'idx_withdrawal_process');
SET @sql := IF(@idx = 0,
  'ALTER TABLE withdrawal ADD INDEX idx_withdrawal_process (status, payout_channel, update_time)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
