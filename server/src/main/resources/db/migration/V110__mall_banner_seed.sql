-- V110：商城轮播初始化数据。
-- 仅在当前没有启用且未删除的商城轮播时写入；运营删除后不会在重复启动时自动恢复。
INSERT INTO mall_banner (
    image_url,
    alt_text,
    target_type,
    target_id,
    sort,
    status
)
SELECT
    seed.image_url,
    seed.alt_text,
    CASE WHEN product.id IS NULL THEN 'none' ELSE 'mallProduct' END,
    CASE WHEN product.id IS NULL THEN '' ELSE CAST(product.id AS CHAR) END,
    seed.sort,
    'enabled'
FROM (
    SELECT
        '/assets/images/3x/profile-banner.jpg' AS image_url,
        '抹茶轻茉莉随行礼盒' AS alt_text,
        'DEMO-MALL-005' AS target_product_code,
        10 AS sort
    UNION ALL
    SELECT '/assets/images/3x/menu-banner.jpg', '一杯暖身心', NULL, 20
    UNION ALL
    SELECT '/assets/images/3x/stored-value-banner.jpg', '金秋入茶限定上新', NULL, 30
) seed
LEFT JOIN mall_product product
    ON product.product_code = seed.target_product_code
   AND product.deleted = 0
WHERE NOT EXISTS (
    SELECT 1
    FROM mall_banner
    WHERE status = 'enabled'
      AND deleted = 0
)
ORDER BY seed.sort;
