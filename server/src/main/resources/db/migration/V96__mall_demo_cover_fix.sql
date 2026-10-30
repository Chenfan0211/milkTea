-- =====================================================================
-- V96: 商城演示商品图片修正
--
-- 设计要点：
--   * 不修改已上线 V95 文件，仅按商品编码幂等修正演示商品图片；
--   * 按商品编码固定映射到现有本地 3x 素材，不新增图片文件；
--   * cover_image、gallery_images、mall_sku.image 保持一致；
--   * 只更新图片字段，不改价格、库存、排序、上下架状态。
-- =====================================================================

-- ---------- 演示商品图片修正 ----------
UPDATE mall_product product
JOIN (
    SELECT 'DEMO-MALL-001' AS product_code, '/assets/images/3x/menu-product.jpg' AS cover_image
    UNION ALL SELECT 'DEMO-MALL-002', '/assets/images/3x/tea-dahongpao.jpg'
    UNION ALL SELECT 'DEMO-MALL-003', '/assets/images/3x/tea-yunling-yimi.jpg'
    UNION ALL SELECT 'DEMO-MALL-004', '/assets/images/3x/gift-card-matcha.jpg'
    UNION ALL SELECT 'DEMO-MALL-005', '/assets/images/3x/gift-card-jasmine.jpg'
    UNION ALL SELECT 'DEMO-MALL-006', '/assets/images/3x/points-product-pet.jpg'
    UNION ALL SELECT 'DEMO-MALL-007', '/assets/images/3x/gift-card-limited.jpg'
    UNION ALL SELECT 'DEMO-MALL-008', '/assets/images/3x/tea-chunyun-xiaozhong.jpg'
) AS seed ON seed.product_code = product.product_code
SET product.cover_image = seed.cover_image,
    product.gallery_images = JSON_ARRAY(seed.cover_image);

-- ---------- 演示 SKU 图片同步 ----------
UPDATE mall_sku sku
JOIN mall_product product
  ON product.id = sku.product_id
 AND product.deleted = 0
SET sku.image = product.cover_image
WHERE product.product_code LIKE 'DEMO-MALL-%';

-- ---------- 演示分类图片修正 ----------
UPDATE mall_category
SET icon_image = CASE code
    WHEN 'tea-goods' THEN '/assets/images/3x/mall-category-tea-goods.jpg'
    WHEN 'living-goods' THEN '/assets/images/3x/mall-category-living-goods.jpg'
    WHEN 'gift-box' THEN '/assets/images/3x/mall-category-gift-box.jpg'
    ELSE icon_image
END
WHERE code IN ('tea-goods', 'living-goods', 'gift-box');
