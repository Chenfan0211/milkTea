-- =====================================================================
-- V64：优惠券「领取后延迟生效」支持（分享有礼券 3 天后生效）
--
-- 背景：
--   分享有礼奖励券自 2026-09 起要求「发放后 3 天才能使用」，用于防刷
--   （好友完成首单即刻退单套取奖励）。原 coupon 表只有 validity_type
--   (RANGE/DAYS)、validity_start/end、validity_days 三种有效期表达，
--   无法表达「领取后延迟 N 天生效、再有效 M 天」。
--
-- 方案：
--   新增 effective_delay_days —— 领取后延迟生效天数，NULL/0 表示立即生效。
--   生效起点 = user_coupon.receive_time + effective_delay_days；
--   过期时间 = 生效起点 + validity_days（即领取后 delay+validity 天到期）。
--
-- 兼容：
--   存量券 effective_delay_days 为 NULL，语义不变（立即生效），
--   不改变任何既有券的可使用时间。
--
-- 幂等：用 information_schema 判断列是否存在后动态执行。
-- =====================================================================

SET @col := 'effective_delay_days';

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'coupon'
                 AND COLUMN_NAME = @col);

SET @sql := IF(@exist = 0,
  'ALTER TABLE coupon ADD COLUMN effective_delay_days INT NULL COMMENT ''领取后延迟生效天数，NULL/0=立即生效'' AFTER validity_days',
  'SELECT ''coupon.effective_delay_days 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 分享有礼券改为「领取后 3 天生效，生效后 15 天有效」（共 18 天）。
-- 只更新尚未被运营手工改过延迟值的分享有礼券模板，避免覆盖人工配置。
UPDATE coupon
   SET effective_delay_days = 3,
       validity_days = 15
 WHERE type = 'REFERRAL'
   AND deleted = 0
   AND (effective_delay_days IS NULL OR effective_delay_days = 0);
