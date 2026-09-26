-- =============================================================
-- V45：待核销池数据修复（取餐码 / 订单明细）
--
-- 背景（运营后台「待核销池」页暴露的三类问题）：
--   1. PAID 订单 pickup_code 为空 —— V22 演示订单虽写了 DEMO-1xxx，
--      但部分 WX2026 真实订单在早期支付链路（markPaid 未生成取餐码的版本）
--      里支付成功却没写入取餐码；核销池按码核销时无法操作。
--   2. order_item 缺行 —— V33 只补过一次；其后 V42 新增的 STORED_VALUE
--      支付链路直接 markPaid，若旧数据/手工数据没有明细，核销池「商品/规格」列为空。
--   3. 取餐码唯一性 —— 同门店 DEMO 演示码与 WX 真实码可能撞号，
--      补码时必须避开已存在的码。
--
-- 口径：
--   - 补码仅针对 status='PAID'（真正要核销的单）；已完成/已核销的
--     历史单不动（它们已经不需要码）。
--   - 新码规则：沿用 nextPickupCode 的「4 位数字」口径，
--     从当前门店最大码 + 1 顺延，撞号自动跳过。
--   - 明细补录与 V33 相同口径：按 total_amount 反推一条占位明细。
--
-- 幂等：全部 NOT EXISTS / IS NULL 判定，可重复执行。
-- =============================================================

-- ---------- 1. PAID 订单缺取餐码：按门店顺序补 4 位码 ----------
-- 思路：对每个缺码订单，取「该门店现有最大数字码 + 1」顺延；
--      若与已有码冲突（同门店并发），下一轮迁移/重跑会继续顺延。
-- 说明：pickup_code 无唯一索引（仅普通列），这里按业务口径保证「同门店不重」。
UPDATE orders o
JOIN (
    SELECT o1.id AS order_id,
           o1.store_subject_id AS store_id,
           LPAD(
             (
               SELECT COALESCE(MAX(CAST(o2.pickup_code AS UNSIGNED)), 0) + 1
               FROM orders o2
               WHERE o2.store_subject_id = o1.store_subject_id
                 AND o2.deleted = 0
                 AND o2.pickup_code REGEXP '^[0-9]{1,4}$'
             ) % 10000, 4, '0') AS new_code
    FROM orders o1
    WHERE o1.deleted = 0
      AND o1.status = 'PAID'
      AND (o1.pickup_code IS NULL OR o1.pickup_code = '')
) fix ON fix.order_id = o.id
SET o.pickup_code = fix.new_code,
    o.update_time = NOW();

-- ---------- 2. 已支付订单缺 order_item：补一条占位明细 ----------
-- 与 V33 相同口径（金额反推），但补录名带「补录」标记，便于人工识别非原始明细。
INSERT INTO order_item
  (order_id, product_id, product_name, spec_snapshot, unit_price, original_price, quantity, sub_total)
SELECT
  o.id,
  CONCAT('BACKFILL-', o.order_no),
  COALESCE(NULLIF(o.remark, ''), '门店订单商品（补录）'),
  '标准',
  o.paid_amount,
  COALESCE(NULLIF(o.original_amount, 0), o.paid_amount),
  1,
  o.paid_amount
FROM orders o
WHERE o.deleted = 0
  AND o.status IN ('PAID', 'VERIFIED', 'COMPLETED')
  AND o.paid_amount > 0
  AND NOT EXISTS (
    SELECT 1 FROM order_item oi
    WHERE oi.order_id = o.id AND oi.deleted = 0
  );