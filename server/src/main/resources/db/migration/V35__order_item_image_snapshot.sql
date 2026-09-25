-- =============================================================
-- V35：订单明细（order_item）增加商品图片快照列并回填历史数据
--
-- 背景：订单详情页（小程序 / 运营后台）需要展示商品图片，但 order_item
--   历史只存了 product_name / spec_snapshot / 价格等，没有图片。
--   商品图片归属 product 域，若实时跨服务查询会产生 N+1 远程调用。
--   故按「订单快照」语义，把商品图片在下单时快照进 order_item，
--   历史订单则按业务商品 ID（product_id）关联 product 表回填。
-- =============================================================

-- 1) 增加商品图片列（Flyway 保证本版本仅执行一次，无需防御式判断）
ALTER TABLE order_item
    ADD COLUMN image VARCHAR(255) NULL COMMENT '商品图（下单时快照）' AFTER spec_snapshot;

-- 2) 回填历史订单图片：按业务商品 ID 关联 product 表（product_id 为业务键，非主键）
UPDATE order_item oi
JOIN product p ON p.product_id = oi.product_id AND p.deleted = 0
SET oi.image = p.image
WHERE oi.deleted = 0
  AND (oi.image IS NULL OR oi.image = '')
  AND p.image IS NOT NULL
  AND p.image <> '';
