-- =====================================================================
-- V60：积分获取规则启用状态
--
-- 需求：
--   规则需要「停用 / 启用」开关；只有启用（enabled=1）才对小程序端
--   生效并展示，停用规则保留在后台供重新启用。
--
-- 幂等：与 V23/V59 一致，用 information_schema 判断后动态执行。
-- =====================================================================

SET @tbl := 'points_earning_rule';

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'enabled');
SET @sql := IF(@exist = 0,
  'ALTER TABLE points_earning_rule ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 COMMENT ''启用状态：1=启用，0=停用'' AFTER daily_limit',
  'SELECT ''enabled 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量规则默认全部启用（保持历史行为不变）
UPDATE points_earning_rule SET enabled = 1 WHERE enabled IS NULL;
