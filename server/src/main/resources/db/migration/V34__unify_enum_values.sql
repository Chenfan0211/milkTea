-- =====================================================================
-- V34：统一运营后台展示涉及的枚举值
--
-- 背景：reconcile_issue.issue_type 存在「实体常量（权威）」与「早期 seed 脏数据」两套命名，
--   导致运营后台「对账异常池」的 issue_type 列 statusMap 无法命中而原样显示英文。
--   本脚本把存量脏数据归并到实体常量（ReconcileIssue）的规范值，保证前端映射一致。
--
-- 权威值（见 finance/entity/ReconcileIssue.java）：
--   MISSING_SPLIT           核销后无分账快照
--   MISSING_REVERSE         退款后未冲正
--   SPLIT_AMOUNT_MISMATCH   分账五方金额与实付不一致
--
-- 脏值（见 V22 seed，DEMO 演示数据）与规范值映射：
--   AMOUNT_MISMATCH   -> SPLIT_AMOUNT_MISMATCH （金额差异）
--   DUPLICATE_PAY     -> SPLIT_AMOUNT_MISMATCH （重复支付，金额不一致）
--   MISSING_PAYMENT   -> MISSING_REVERSE        （缺失支付流水）
--   MISSING_ORDER     -> MISSING_REVERSE        （缺失订单）
--   STATUS_MISMATCH   -> MISSING_REVERSE        （状态不一致）
-- =====================================================================

UPDATE reconcile_issue
SET issue_type = CASE issue_type
    WHEN 'AMOUNT_MISMATCH' THEN 'SPLIT_AMOUNT_MISMATCH'
    WHEN 'DUPLICATE_PAY'   THEN 'SPLIT_AMOUNT_MISMATCH'
    WHEN 'MISSING_PAYMENT' THEN 'MISSING_REVERSE'
    WHEN 'MISSING_ORDER'   THEN 'MISSING_REVERSE'
    WHEN 'STATUS_MISMATCH' THEN 'MISSING_REVERSE'
    ELSE issue_type
END
WHERE deleted = 0
  AND issue_type IN ('AMOUNT_MISMATCH', 'DUPLICATE_PAY', 'MISSING_PAYMENT', 'MISSING_ORDER', 'STATUS_MISMATCH');

-- =====================================================================
-- 对账状态：历史 seed 混入 PROCESSING（对账状态机无此态），归并为 OPEN（待处理）。
-- 权威值（ReconcileIssue）：OPEN / RESOLVED / IGNORED。
-- =====================================================================
UPDATE reconcile_issue
SET status = 'OPEN'
WHERE deleted = 0
  AND status = 'PROCESSING';
