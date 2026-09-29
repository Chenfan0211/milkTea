-- =============================================================
-- V66：app_user 增加「详细地址」字段
--
-- 背景：
--   个人资料页需要支持用户手动填写详细地址（文本框输入），
--   但 app_user 表此前没有对应列，改完只能存本地缓存，
--   冷启动 refreshUserProfileFromRemote 会被后端旧值覆盖
--   （表现为「保存无效」）。region 表是行政区划字典，不能复用。
--
-- 目标：
--   新增 address VARCHAR(255)，存用户手填的详细地址文本。
--
-- 幂等：用 information_schema 判断列是否存在后动态执行。
-- =============================================================

SET @col := 'address';

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'app_user'
                 AND COLUMN_NAME = @col);

SET @sql := IF(@exist = 0,
  'ALTER TABLE app_user ADD COLUMN address VARCHAR(255) NULL COMMENT ''详细地址（用户手填）'' AFTER gender',
  'SELECT ''app_user.address 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
