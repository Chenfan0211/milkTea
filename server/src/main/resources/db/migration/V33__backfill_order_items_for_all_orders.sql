-- =============================================================
-- V33：为所有缺明细的订单补齐 order_item
--
-- 背景（真实故障）：V27 只为 10 条 DEMO-O2026% 演示订单补了明细。
--       用户在小程序实际下单（order_no 形如 WX2026...）后，orders 主表有记录，
--       但 order_item 没有对应行，导致订单详情接口返回
--       { code: 404, message: "订单不存在" }（OrderService.getByOrderNo 查不到明细时
--       会渲染成空详情，前端表现为「订单不存在」）。
--
-- 本迁移为**所有** deleted=0 且没有任何 order_item 的订单补一条占位明细，
-- 数据来源优先级：
--   1) 该订单金额 total_amount 反推一条「订单商品」明细；
--   2) 若订单有 remark 则以 remark 作为商品名。
-- 这样历史订单与用户新下的订单都能在详情页正常展示。
--
-- 幂等：NOT EXISTS 判重，可重复执行。
-- 范围：仅补缺失明细，不覆盖已有明细。
-- =============================================================

INSERT INTO order_item
  (order_id, product_id, product_name, spec_snapshot, unit_price, original_price, quantity, sub_total)
SELECT
  o.id,
  CONCAT('LEGACY-', o.order_no)                       AS product_id,
  COALESCE(NULLIF(o.remark, ''), '门店订单商品')      AS product_name,
  '历史订单（系统补录）'                              AS spec_snapshot,
  o.total_amount                                      AS unit_price,
  COALESCE(NULLIF(o.original_amount, 0), o.total_amount) AS original_price,
  1                                                   AS quantity,
  o.total_amount                                      AS sub_total
FROM orders o
WHERE o.deleted = 0
  AND o.total_amount > 0
  AND NOT EXISTS (
    SELECT 1 FROM order_item oi
    WHERE oi.order_id = o.id AND oi.deleted = 0
  );

-- 说明：
--   1) unit_price 使用 total_amount（后端金额单位为「分」），
--      保证详情页「合计」与订单主表 paid_amount/total_amount 一致。
--   2) spec_snapshot 必须写**纯文本**，不能写 JSON_OBJECT：
--      后端 OrderItem.specSnapshot 是 String，MyBatis 会原样返回列的文本，
--      写 JSON 会导致小程序规格行显示成 {"summary":"..."} 这样的原始 JSON。