-- =============================================================
-- V42：分账明细口径修正后的支付记录页配套变更
--
-- 变更点：
--   1. 储值支付单关联订单号（业务口径调整）——
--      原 V24 让储值支付单的 order_no / order_id 留空，认为「储值不关联订单」。
--      但运营后台「支付记录」需要按订单号检索，且业务上
--         · 储值「充值」不可退（钱进余额，不做退款）；
--         · 用储值「余额」下单买商品可退（走普通订单退款链路）。
--      因此储值支付单也必须能落到一个订单号上，才能与订单退款对账。
--      这里不再把储值单号（CZ…）塞进 order_no，而是明确：
--        · order_no  = 关联的系统订单号（储值余额下单时 = 订单号；纯充值单可为 NULL）
--        · biz_no    = 业务单号（储值充值 = CZ 单号；订单支付 = order_no）
--        · transaction_id = 三方流水订单号（储值支付时回填为订单号，见下）
--   2. 三方流水订单号兜底回填：历史数据里 transaction_id 为空的，
--      用 order_no 回填，保证后台「流水订单号」列不再大面积空白。
--      仅回填已成功的支付单（PAYING/未完成的不该有流水号）。
--   3. 支付渠道新增 STORED_VALUE，需补充索引便于按渠道筛选。
--
-- 说明：只做列语义与数据回填，不改表结构。
-- =============================================================

-- ---------- 1. 流水订单号兜底回填（已成功但缺 transaction_id 的历史单）----------
-- 口径：流水订单号 = 三方订单号；储值支付时等于系统订单号。
UPDATE payment
SET transaction_id = order_no
WHERE deleted = 0
  AND (transaction_id IS NULL OR transaction_id = '')
  AND order_no IS NOT NULL
  AND standard_status = 'PAID';

-- ---------- 2. 储值支付单补齐 biz_no ----------
-- 兜底：biz_no 为空但有 order_no 的，用 order_no 回填（订单支付口径）。
UPDATE payment
SET biz_no = order_no
WHERE deleted = 0
  AND (biz_no IS NULL OR biz_no = '')
  AND order_no IS NOT NULL;

-- ---------- 3. 按渠道筛选的索引 ----------
-- 后台支付记录支持「支付渠道」列展示与状态筛选，补 (channel, standard_status) 组合索引。
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'payment'
                 AND INDEX_NAME = 'idx_payment_channel_status');
SET @sql := IF(@exist = 0,
  'ALTER TABLE payment ADD INDEX idx_payment_channel_status (channel, standard_status)',
  'SELECT ''idx_payment_channel_status 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. 按三方流水订单号检索的索引 ----------
-- 后台搜索项「流水订单号」需按 transaction_id 模糊匹配，无索引会全表扫。
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'payment'
                 AND INDEX_NAME = 'idx_payment_transaction_id');
SET @sql := IF(@exist = 0,
  'ALTER TABLE payment ADD INDEX idx_payment_transaction_id (transaction_id)',
  'SELECT ''idx_payment_transaction_id 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;