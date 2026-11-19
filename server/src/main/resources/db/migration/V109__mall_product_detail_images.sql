-- V109：商城商品图文详情长图。
-- gallery_images 继续承担顶部商品主图轮播；detail_images 仅用于商品说明下方的纵向长图，
-- 有序 JSON 字符串数组，已有商品保持 NULL，不批量回填。
SET @detail_images_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mall_product'
      AND COLUMN_NAME = 'detail_images'
);
SET @detail_images_ddl := IF(
    @detail_images_exists = 0,
    'ALTER TABLE mall_product ADD COLUMN detail_images JSON NULL COMMENT ''商品详情长图URL数组'' AFTER gallery_images',
    'SELECT 1'
);
PREPARE detail_images_stmt FROM @detail_images_ddl;
EXECUTE detail_images_stmt;
DEALLOCATE PREPARE detail_images_stmt;
