-- =============================================================
-- V80 舌诊 · 养生茶饮商品初始化
--
-- 背景：详情页「饮食」Tab 的养生茶饮卡片按「商品名称严格匹配」报告推荐商品
--       （去空白 + 忽略大小写），因此把运营指定的 4 款茶饮建到商品库；
--       同时新增「适宜体质」列，供卡片按参考图展示。
--
-- 幂等：DDL 用 information_schema 守卫，DML 用 product_id 判重，
--       可重复执行（本次先手工执行，Flyway 会在下次全量部署时补记录）。
-- 注意：商品正常上架（on_sale=1），因此点单页菜单同样可见。
-- =============================================================

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'product'
      AND column_name = 'suitable_constitutions'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE product ADD COLUMN suitable_constitutions JSON NULL COMMENT ''适宜体质（舌诊养生茶饮卡片展示）'' AFTER tags',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO product (product_id, code, name, category_id, tags, suitable_constitutions,
                     description, price, original_price, stored_value_price, image, on_sale)
SELECT t.product_id, t.code, t.name, t.category_id, t.tags, t.suitable_constitutions,
       t.description, t.price, t.original_price, t.stored_value_price, t.image, t.on_sale
FROM (
    SELECT 'tea-gongting-shupu' AS product_id, 'TEA-GTSP' AS code, '宫廷熟普' AS name, 5 AS category_id,
           JSON_ARRAY('醇滑质感', '经典传承') AS tags,
           JSON_ARRAY('痰湿质', '阳虚质', '气虚质', '血瘀质') AS suitable_constitutions,
           '宫廷熟普：醇滑质感，经典传承，适合日常温养。' AS description,
           1 AS price, 1 AS original_price, 1 AS stored_value_price,
           '/assets/images/3x/tea-gongting-shupu.jpg' AS image, 1 AS on_sale
    UNION ALL
    SELECT 'tea-chunyun-xiaozhong', 'TEA-CYXZ', '醇韵小种', 5,
           JSON_ARRAY('醇厚悠长', '松烟香显'), JSON_ARRAY('痰湿质'),
           '醇韵小种：醇厚悠长，松烟香显。', 1, 1, 1, '/assets/images/3x/tea-chunyun-xiaozhong.jpg', 1
    UNION ALL
    SELECT 'tea-dahongpao', 'TEA-DHP', '大红袍', 5,
           JSON_ARRAY('半发酵艺', '香高味醇'), JSON_ARRAY('阳虚质', '痰湿质', '气郁质', '平和质'),
           '大红袍：半发酵艺，香高味醇。', 990, 990, 990, '/assets/images/3x/tea-dahongpao.jpg', 1
    UNION ALL
    SELECT 'tea-yunling-yimi', 'TEA-YLM', '云苓薏米清畅茶', 3,
           JSON_ARRAY('温和草本', '自然之味'), JSON_ARRAY('痰湿质'),
           '云苓薏米清畅茶：温和草本，自然之味。', 990, 990, 990, '/assets/images/3x/tea-yunling-yimi.jpg', 1
) AS t
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.product_id = t.product_id);