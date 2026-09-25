-- =============================================================
-- V27：为演示订单补齐订单明细（order_item）
--
-- 背景：V22 只插入了 orders 主表，未插入对应的 order_item 明细，
--       导致小程序订单列表/详情页无商品明细可渲染。
-- 本迁移为 10 条演示订单各补 1 条明细，关联对应演示商品。
--
-- 幂等：可重复执行（按 order_id + product_id + deleted=0 判重）。
-- =============================================================

INSERT INTO order_item (order_id, product_id, product_name, spec_snapshot, unit_price, original_price, quantity, sub_total)
SELECT o.id, p.product_id, p.name, '中杯,标准冰', p.price, p.original_price, 1, p.price
FROM orders o
JOIN product p ON p.product_id = CASE o.order_no
    WHEN 'DEMO-O20260001' THEN 'DEMO-P001'
    WHEN 'DEMO-O20260002' THEN 'DEMO-P002'
    WHEN 'DEMO-O20260003' THEN 'DEMO-P003'
    WHEN 'DEMO-O20260004' THEN 'DEMO-P004'
    WHEN 'DEMO-O20260005' THEN 'DEMO-P005'
    WHEN 'DEMO-O20260006' THEN 'DEMO-P006'
    WHEN 'DEMO-O20260007' THEN 'DEMO-P007'
    WHEN 'DEMO-O20260008' THEN 'DEMO-P008'
    WHEN 'DEMO-O20260009' THEN 'DEMO-P009'
    WHEN 'DEMO-O20260010' THEN 'DEMO-P010'
END
WHERE o.order_no LIKE 'DEMO-O202600%' AND o.deleted = 0 AND p.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM order_item oi WHERE oi.order_id = o.id AND oi.product_id = p.product_id AND oi.deleted = 0
  );
