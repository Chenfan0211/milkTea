-- =============================================================
-- V23：储值套餐「使用说明」列
--
-- 背景：储值页需要展示「退款规则」等说明文字。后台已按「每套餐独立」的语义
--       在编辑这些文案（见 src/views/marketing/stored/index.vue），
--       但 stored_value_package 表没有对应列，导致配置无处落脚。
--
-- 决策：作为套餐字段存 JSON 数组（每行一条），而非全局 app_config。
--   理由：不同面额的退款示例金额不同（如「充值100元为例...」），
--         共用一份说明会导致文案与面额对不上。
--
-- 格式：JSON 数组，元素为字符串，例如
--   ["1、本储值套餐包含：储值金额100元...", "2、储值赠送的券自充值当天起365天有效..."]
--   为空或 NULL 时，小程序端回落到内置默认说明。
--
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
--       故用 information_schema 判断后动态执行。
-- =============================================================

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'stored_value_package'
                 AND COLUMN_NAME = 'usage_paragraphs');
SET @sql := IF(@exist = 0,
  'ALTER TABLE stored_value_package ADD COLUMN usage_paragraphs JSON NULL COMMENT ''使用说明（JSON 字符串数组，每行一条）'' AFTER status',
  'SELECT ''usage_paragraphs 已存在，跳过'' AS message');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 为既有 3 个套餐补默认说明 ----------
-- 仅在列为空时写入，避免覆盖运营在后台已改过的内容。
UPDATE stored_value_package
SET usage_paragraphs = JSON_ARRAY(
  CONCAT('1、本储值套餐包含：储值金额', amount DIV 100, '元及对应赠送优惠券（满9.9可使用）。'),
  '2、储值赠送的券自充值当天起365天有效，请在有效期内尽快使用，单笔订单仅限使用一张优惠券。',
  '3、退款：(1) 成功充值后如有退款需求，可通过小程序「我的」-「联系客服」咨询；(2) 退款时如未使用赠送券，储值金与优惠券完整退回；如已使用，则扣除已用券面额后退回剩余金额。',
  '最终解释权归五零时光所有。'
)
WHERE (usage_paragraphs IS NULL OR JSON_LENGTH(usage_paragraphs) = 0)
  AND deleted = 0;
