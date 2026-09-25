-- =============================================================
-- V24：payment 表新增「业务类型」，支持储值充值等非订单支付
--
-- 【为什么必须加这一列】
-- payment 表原先只有 order_id / order_no，语义上「支付单 = 订单的支付」。
-- 但储值充值的单据是 stored_value_order（marketing 域），与订单（trade 域）
-- 是两套独立的单据模型。
--
-- 微信支付回调只带回 out_trade_no。若不加业务类型区分，
-- PaymentService.handleWxPayCallback 会拿储值单号（CZ 前缀）去查 order 表，
-- 必然查不到 → 回调处理失败 → 用户已付款但余额永不入账（资损）。
--
-- 因此新增 biz_type 用于回调路由：
--   ORDER        —— 点单订单支付（默认，兼容历史数据）
--   STORED_VALUE —— 储值充值
--
-- 同时把 order_id / order_no 放宽为可空：储值支付单不关联订单表。
--
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，用 information_schema 判断。
-- =============================================================

-- ---------- 1. 新增 biz_type ----------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'payment'
                 AND COLUMN_NAME = 'biz_type');
SET @sql := IF(@exist = 0,
  'ALTER TABLE payment ADD COLUMN biz_type VARCHAR(32) NOT NULL DEFAULT ''ORDER'' COMMENT ''业务类型：ORDER 订单 / STORED_VALUE 储值充值'' AFTER order_no',
  'SELECT ''biz_type 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. 新增 biz_no（业务单号，与 order_no 解耦）----------
-- 储值场景下 order_no 为空，回调需靠 biz_no 找到业务单据。
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'payment'
                 AND COLUMN_NAME = 'biz_no');
SET @sql := IF(@exist = 0,
  'ALTER TABLE payment ADD COLUMN biz_no VARCHAR(64) NULL COMMENT ''业务单号（储值单号等；订单支付时同 order_no）'' AFTER biz_type',
  'SELECT ''biz_no 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 3. 历史数据回填：既有支付单均为订单支付 ----------
UPDATE payment SET biz_type = 'ORDER' WHERE biz_type IS NULL OR biz_type = '';
UPDATE payment SET biz_no = order_no WHERE (biz_no IS NULL OR biz_no = '') AND order_no IS NOT NULL;

-- ---------- 4. 放宽 order_id / order_no 为可空 ----------
-- 储值支付单不关联 order 表，若保持 NOT NULL 会导致插入失败。
-- MySQL 未变更列时重复 MODIFY 是安全的（幂等）。
ALTER TABLE payment MODIFY COLUMN order_id BIGINT UNSIGNED NULL COMMENT '订单 ID（储值支付为 NULL）';
ALTER TABLE payment MODIFY COLUMN order_no VARCHAR(64) NULL COMMENT '订单号（储值支付为 NULL）';

-- ---------- 5. 回调路由索引 ----------
-- 微信回调按 out_trade_no 反查支付单，储值场景走 biz_no。
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'payment'
                 AND INDEX_NAME = 'idx_payment_biz');
SET @sql := IF(@exist = 0,
  'ALTER TABLE payment ADD INDEX idx_payment_biz (biz_type, biz_no)',
  'SELECT ''idx_payment_biz 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 6. 储值订单补充「支付单号」回写位 ----------
-- 储值订单在支付成功后回写微信交易号，便于对账与退款。
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'stored_value_order'
                 AND COLUMN_NAME = 'transaction_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE stored_value_order ADD COLUMN transaction_id VARCHAR(64) NULL COMMENT ''微信支付交易号'' AFTER pay_status',
  'SELECT ''stored_value_order.transaction_id 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'stored_value_order'
                 AND COLUMN_NAME = 'payer_openid');
SET @sql := IF(@exist = 0,
  'ALTER TABLE stored_value_order ADD COLUMN payer_openid VARCHAR(64) NULL COMMENT ''支付者 openid（退款原路退回必需）'' AFTER transaction_id',
  'SELECT ''stored_value_order.payer_openid 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
