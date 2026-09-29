-- =============================================================
-- V69：提现对接微信「商家转账到零钱」出款字段
--
-- 背景：提现出款从「同步标记 PAID」升级为对接微信转账批次
-- （transfer_batches）。转账是异步的，需要记录微信侧批次号与
-- 转账状态，并在回调/主动查询后据此收敛提现状态。
--
-- 幂等：用 information_schema 判断字段是否存在后再执行。
-- =============================================================

-- 微信转账批次号（batch_id）：发起转账受理后由微信返回
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'transfer_batch_no');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN transfer_batch_no VARCHAR(64) NULL COMMENT ''微信转账批次号(batch_id)''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 微信转账状态（SUCCESS / FAILED / PROCESSING / ACCEPTED 等）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'transfer_status');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN transfer_status VARCHAR(32) NULL COMMENT ''微信转账状态''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 微信转账失败原因（受理失败或转账失败时写入）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'withdrawal'
               AND COLUMN_NAME = 'transfer_fail_msg');
SET @sql := IF(@col = 0,
  'ALTER TABLE withdrawal ADD COLUMN transfer_fail_msg VARCHAR(255) NULL COMMENT ''微信转账失败原因''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
