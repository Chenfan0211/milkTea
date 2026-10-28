-- =====================================================================
-- V94: 商城分类图标
--
-- 设计要点：
--   * 分类图保存文件服务返回的公开 URL，不保存二进制或 Data URL；
--   * 存量分类保持 NULL，小程序端统一降级为本地占位图；
--   * 字段新增使用 information_schema 判断，保证重复执行安全。
-- =====================================================================

SET @has_icon_image := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mall_category'
      AND column_name = 'icon_image'
);

SET @add_icon_image := IF(
    @has_icon_image = 0,
    'ALTER TABLE `mall_category` ADD COLUMN `icon_image` VARCHAR(255) NULL COMMENT ''分类图标URL'' AFTER `name`',
    'SELECT 1'
);

PREPARE STMT FROM @add_icon_image;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
