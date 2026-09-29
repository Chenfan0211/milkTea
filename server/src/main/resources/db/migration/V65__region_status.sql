-- =============================================================
-- V65：region 增加启用/停用状态
--
-- 背景：运营后台「城市管理」需要支持停用/启用，且仅停用状态允许删除。
-- region 表此前没有 status 列，这里补上（enabled 启用 / disabled 停用），
-- 默认 enabled 以兼容既有数据与迁移灌入的全国行政区划。
--
-- 幂等：用 information_schema 判断列是否存在后再执行。
-- =============================================================

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'region'
             AND COLUMN_NAME = 'status');
SET @s := IF(@c = 0,
  'ALTER TABLE region ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''enabled'' COMMENT ''状态 enabled启用 disabled停用'' AFTER level',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
