-- =====================================================================
-- V95: 商城演示数据
--
-- 设计要点：
--   * 仅补充可识别的 DEMO-MALL-* 商品，便于验收视觉与分类筛选；
--   * 主体与分账规则通过现有编码动态取 id，不硬编码主键；
--   * 全部写入使用 ON DUPLICATE KEY UPDATE，迁移可重复执行；
--   * 商品封面复用小程序本地 3x 素材，不新增主包资源。
-- =====================================================================

SET @mall_store_subject_id := (
    SELECT id FROM biz_subject
    WHERE code = 'ST-1001' AND deleted = 0
    LIMIT 1
);

SET @mall_supplier_subject_id := (
    SELECT id FROM biz_subject
    WHERE code = 'SU-1000' AND deleted = 0
    LIMIT 1
);

SET @mall_split_rule_id := (
    SELECT id FROM split_rule
    WHERE code = 'SR-1000' AND deleted = 0
    LIMIT 1
);

-- ---------- V94 依赖守卫 ----------
SET @mall_icon_ready := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'mall_category'
      AND column_name = 'icon_image'
);

SET @abort_migration := IF(
    @mall_icon_ready = 0,
    'V95 依赖缺失：请先执行 V94__mall_category_icon.sql，再执行商城演示数据迁移',
    'SELECT 1'
);

SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @abort_migration;

-- ---------- 主体与分账规则依赖守卫 ----------
SET @abort_seed_dependencies := IF(
    @mall_store_subject_id IS NULL
    OR @mall_supplier_subject_id IS NULL
    OR @mall_split_rule_id IS NULL,
    'V95 依赖缺失：必须存在 ST-1001、SU-1000 与 SR-1000，禁止写入空主体',
    'SELECT 1'
);

SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = @abort_seed_dependencies;
-- ---------- 演示分类 ----------
INSERT INTO mall_category (code, name, icon_image, sort, status) VALUES
('tea-goods', '茶器好物', '/assets/images/3x/tea-dahongpao.jpg', 10, 'ENABLED'),
('living-goods', '轻养生活', '/assets/images/3x/gift-card-matcha.jpg', 20, 'ENABLED'),
('gift-box', '茶礼甄选', '/assets/images/3x/gift-card-limited.jpg', 30, 'ENABLED')
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    icon_image = VALUES(icon_image),
    sort = VALUES(sort),
    status = VALUES(status),
    deleted = 0;

-- ---------- 演示商品 ----------
INSERT INTO mall_product (
    product_code, category_id, name, subtitle, spec_name,
    cover_image, gallery_images, description,
    attribution_store_subject_id, split_rule_id, status, sort, sales_count
)
SELECT
    seed.product_code,
    category.id,
    seed.name,
    seed.subtitle,
    '标准款',
    seed.cover_image,
    JSON_ARRAY(seed.cover_image),
    seed.description,
    @mall_store_subject_id,
    @mall_split_rule_id,
    'ON_SALE',
    seed.sort,
    seed.sales_count
FROM (
    SELECT 'DEMO-MALL-001' AS product_code, 'tea-goods' AS category_code, '功夫茶具套装' AS name,
           '白瓷盖碗 · 一壶四杯' AS subtitle, '/assets/images/3x/tea-gongting-shupu.jpg' AS cover_image,
           '精选白瓷茶具，家用待客都合适。' AS description, 10 AS sort, 128 AS sales_count
    UNION ALL SELECT 'DEMO-MALL-002', 'tea-goods', '陈年大红袍礼罐', '岩骨花香 · 罐装 100g',
           '/assets/images/3x/tea-dahongpao.jpg', '传统炭焙工艺，条索紧结，回甘明显。', 20, 96
    UNION ALL SELECT 'DEMO-MALL-003', 'tea-goods', '云南易武古树茶', '山野气韵 · 357g 饼茶',
           '/assets/images/3x/tea-yunling-yimi.jpg', '古树春茶原料，耐泡度高，适合日常品饮。', 30, 64
    UNION ALL SELECT 'DEMO-MALL-004', 'living-goods', '便携随手茶杯', '双层隔热 · 一键滤茶',
           '/assets/images/3x/gift-card-matcha.jpg', '办公室与出行都好用的轻养茶具。', 10, 152
    UNION ALL SELECT 'DEMO-MALL-005', 'living-goods', '抹茶轻茉莉随行礼盒', '清新抹茶 · 茉莉香气',
           '/assets/images/3x/gift-card-jasmine.jpg', '含独立小罐茶粉，随冲随饮。', 20, 88
    UNION ALL SELECT 'DEMO-MALL-006', 'living-goods', '时光茶宠摆件', '手工陶制 · 茶桌伴手',
           '/assets/images/3x/points-product-pet.jpg', '温润陶土茶宠，遇水更显色泽。', 30, 42
    UNION ALL SELECT 'DEMO-MALL-007', 'gift-box', '限定茶礼双罐装', '节日送礼 · 双罐装',
           '/assets/images/3x/gift-card-limited.jpg', '限定礼盒设计，附手提袋与祝福卡。', 10, 76
    UNION ALL SELECT 'DEMO-MALL-008', 'gift-box', '春云小种尝鲜装', '花香小种 · 体验装',
           '/assets/images/3x/tea-chunyun-xiaozhong.jpg', '小分量体验装，先尝后囤。', 20, 35
) AS seed
JOIN mall_category category ON category.code = seed.category_code AND category.deleted = 0
ON DUPLICATE KEY UPDATE
    category_id = VALUES(category_id),
    name = VALUES(name),
    subtitle = VALUES(subtitle),
    cover_image = VALUES(cover_image),
    gallery_images = VALUES(gallery_images),
    description = VALUES(description),
    attribution_store_subject_id = VALUES(attribution_store_subject_id),
    split_rule_id = VALUES(split_rule_id),
    status = VALUES(status),
    sort = VALUES(sort),
    sales_count = VALUES(sales_count),
    deleted = 0;

-- ---------- 演示 SKU ----------
INSERT INTO mall_sku (
    product_id, sku_code, spec_value, image,
    sale_price, original_price, cost_price, platform_commission,
    supplier_subject_id, stock, locked_stock, sort
)
SELECT
    product.id,
    seed.sku_code,
    '标准款',
    product.cover_image,
    seed.sale_price,
    seed.original_price,
    seed.cost_price,
    seed.platform_commission,
    @mall_supplier_subject_id,
    seed.stock,
    0,
    1
FROM (
    SELECT 'DEMO-MALL-001' AS product_code, 'DEMO-MALL-SKU-001' AS sku_code,
           12900 AS sale_price, 16900 AS original_price, 6000 AS cost_price,
           500 AS platform_commission, 36 AS stock
    UNION ALL SELECT 'DEMO-MALL-002', 'DEMO-MALL-SKU-002', 8800, 0, 4200, 300, 52
    UNION ALL SELECT 'DEMO-MALL-003', 'DEMO-MALL-SKU-003', 16800, 0, 8200, 600, 28
    UNION ALL SELECT 'DEMO-MALL-004', 'DEMO-MALL-SKU-004', 4500, 5900, 2100, 200, 88
    UNION ALL SELECT 'DEMO-MALL-005', 'DEMO-MALL-SKU-005', 15900, 0, 7600, 600, 24
    UNION ALL SELECT 'DEMO-MALL-006', 'DEMO-MALL-SKU-006', 6600, 0, 3100, 300, 15
    UNION ALL SELECT 'DEMO-MALL-007', 'DEMO-MALL-SKU-007', 19900, 25900, 9200, 800, 12
    UNION ALL SELECT 'DEMO-MALL-008', 'DEMO-MALL-SKU-008', 3900, 0, 1800, 200, 0
) AS seed
JOIN mall_product product ON product.product_code = seed.product_code AND product.deleted = 0
ON DUPLICATE KEY UPDATE
    product_id = VALUES(product_id),
    spec_value = VALUES(spec_value),
    image = VALUES(image),
    sale_price = VALUES(sale_price),
    original_price = VALUES(original_price),
    cost_price = VALUES(cost_price),
    platform_commission = VALUES(platform_commission),
    supplier_subject_id = VALUES(supplier_subject_id),
    stock = VALUES(stock),
    locked_stock = VALUES(locked_stock),
    sort = VALUES(sort),
    deleted = 0;
