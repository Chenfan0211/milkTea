-- 仅修正本轮 QA 单 MO20261109122108225 的漏记 1 分；执行前有完整数据库备份。
-- 幂等条件：订单已完成、无团长、原快照及结算记录仍为错误的 1 分、未执行过本次修正。
START TRANSACTION;
SELECT available_balance, total_income INTO @before_balance, @before_income
FROM subject_account WHERE id = 1 AND subject_id = 1 FOR UPDATE;
SET @can_fix = (
  SELECT COUNT(*) = 1 FROM orders o
  JOIN split_snapshot s ON s.order_id = o.id
  JOIN settlement_record r ON r.snapshot_id = s.id AND r.id = 122
  WHERE o.id = 999100143 AND o.order_no = 'MO20261109122108225'
    AND o.status = 'COMPLETED' AND o.paid_amount = 2 AND o.leader_subject_id IS NULL
    AND s.id = 186 AND s.platform_amount = 1 AND s.leader_amount = 1
    AND s.platform_commission = 0 AND s.platform_bonus = 1
    AND r.subject_id = 1 AND r.amount = 1 AND r.original_amount = 1
    AND r.refunded_amount = 0 AND r.status = 'SETTLED'
    AND NOT EXISTS (SELECT 1 FROM fund_flow f WHERE f.flow_no = 'FFQA20261109NOLDR0001')
);
UPDATE split_snapshot
SET platform_amount = 2, leader_amount = 0, platform_bonus = 2
WHERE id = 186 AND @can_fix = 1;
UPDATE settlement_record
SET amount = 2, original_amount = 2
WHERE id = 122 AND @can_fix = 1;
UPDATE subject_account
SET available_balance = available_balance + 1,
    total_income = total_income + 1,
    version = version + 1
WHERE id = 1 AND subject_id = 1 AND @can_fix = 1;
INSERT INTO fund_flow
  (flow_no, subject_id, role_type, type, direction, amount, order_no,
   balance_after, remark, account_id, order_id, settlement_record_id,
   biz_type, biz_no, balance_bucket, balance_before, change_amount, settlement_status)
SELECT 'FFQA20261109NOLDR0001', 1, 'PLATFORM', 'INCOME', 'in', 1,
       'MO20261109122108225', @before_balance + 1,
       'QA 无团长订单漏记分销差额补入平台', 1, 999100143, 122,
       'ORDER', 'MO20261109122108225', 'AVAILABLE', @before_balance, 1, 'SETTLED'
WHERE @can_fix = 1;
COMMIT;
SELECT @can_fix AS applied;
