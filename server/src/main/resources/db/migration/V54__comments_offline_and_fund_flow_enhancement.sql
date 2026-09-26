-- =====================================================================
-- V54：评论功能下线与资金流水增强
--
-- 目标：
--   1) 为 fund_flow 补齐账户、订单、结算、业务关联、余额口径及金额快照字段；
--   2) 为新增关联字段建立查询索引；
--   3) 仅在可唯一推导时回填历史 account_id / order_id / settlement_record_id；
--   4) 逻辑删除营销评论菜单及角色菜单关联，保留评论业务表和历史数据；
--   5) 为退款对账缺口补充 reconcile_issue.refund_no。
--
-- 边界：
--   · 所有金额均为分；amount 继续保存绝对值，change_amount 保存带符号变动值；
--   · 历史 flow 的新增字段允许为空，不批量回填余额、结算状态或结算结果；
--   · 不修改 subject_account 余额，不归档/删除 marketing_comment 数据；
--   · DDL 通过 information_schema 判断存在性，可重复执行。
-- =====================================================================

-- ---------- 1. fund_flow：账户、订单、结算与余额快照字段 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'account_id') = 0,
    'ALTER TABLE fund_flow ADD COLUMN account_id BIGINT UNSIGNED NULL COMMENT ''资金账户ID；历史数据缺失时为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'order_id') = 0,
    'ALTER TABLE fund_flow ADD COLUMN order_id BIGINT UNSIGNED NULL COMMENT ''订单ID；历史数据缺失时为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'settlement_record_id') = 0,
    'ALTER TABLE fund_flow ADD COLUMN settlement_record_id BIGINT UNSIGNED NULL COMMENT ''结算记录ID；历史数据缺失时为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'biz_type') = 0,
    'ALTER TABLE fund_flow ADD COLUMN biz_type VARCHAR(32) NULL COMMENT ''业务类型：ORDER/REFUND/WITHDRAWAL/MANUAL''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'biz_no') = 0,
    'ALTER TABLE fund_flow ADD COLUMN biz_no VARCHAR(64) NULL COMMENT ''业务单号：订单号/退款单号/提现单号/手工业务号''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'balance_bucket') = 0,
    'ALTER TABLE fund_flow ADD COLUMN balance_bucket VARCHAR(16) NULL COMMENT ''余额口径：AVAILABLE/FROZEN；历史数据缺失时为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'balance_before') = 0,
    'ALTER TABLE fund_flow ADD COLUMN balance_before BIGINT NULL COMMENT ''变动前余额(分)；历史数据缺失时为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'change_amount') = 0,
    'ALTER TABLE fund_flow ADD COLUMN change_amount BIGINT NULL COMMENT ''变动金额(分，正增负减)；历史数据缺失时为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND COLUMN_NAME = 'settlement_status') = 0,
    'ALTER TABLE fund_flow ADD COLUMN settlement_status VARCHAR(32) NULL COMMENT ''结算状态：PENDING/SETTLEABLE/FROZEN/SETTLED/CANCELED；非订单业务为空''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. fund_flow：新增关联索引 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND INDEX_NAME = 'idx_fund_flow_account') = 0,
    'ALTER TABLE fund_flow ADD INDEX idx_fund_flow_account (account_id)',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND INDEX_NAME = 'idx_fund_flow_order') = 0,
    'ALTER TABLE fund_flow ADD INDEX idx_fund_flow_order (order_id)',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND INDEX_NAME = 'idx_fund_flow_biz_no') = 0,
    'ALTER TABLE fund_flow ADD INDEX idx_fund_flow_biz_no (biz_no)',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'fund_flow'
       AND INDEX_NAME = 'idx_fund_flow_settlement_record') = 0,
    'ALTER TABLE fund_flow ADD INDEX idx_fund_flow_settlement_record (settlement_record_id)',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 3. 历史流水：仅回填可唯一推导的关联 ID ----------
-- 3.1 account_id：subject_account 对 subject_id 有唯一键，可直接可靠关联。
UPDATE fund_flow f
JOIN subject_account a ON a.subject_id = f.subject_id
SET f.account_id = a.id
WHERE f.account_id IS NULL;

-- 3.2 order_id：orders.order_no 有唯一键；仅当历史 flow.order_no 能唯一匹配时回填。
UPDATE fund_flow f
JOIN orders o ON o.order_no = f.order_no
SET f.order_id = o.id
WHERE f.order_id IS NULL
  AND f.order_no IS NOT NULL
  AND f.order_no <> '';

-- 3.3 settlement_record_id：必须按订单 + 主体唯一匹配，避免误关联多条结算记录。
UPDATE fund_flow f
JOIN settlement_record sr
  ON sr.order_id = f.order_id
 AND sr.subject_id = f.subject_id
SET f.settlement_record_id = sr.id
WHERE f.settlement_record_id IS NULL
  AND f.order_id IS NOT NULL
  AND (
      SELECT COUNT(*)
      FROM settlement_record sr_count
      WHERE sr_count.order_id = f.order_id
        AND sr_count.subject_id = f.subject_id
  ) = 1;

-- 明确不根据历史值猜测：balance_before / change_amount / balance_after /
-- settlement_status / balance_bucket / biz_type / biz_no 均保持原值或空值。

-- ---------- 4. 评论审核菜单逻辑下线 ----------
-- 先解除角色关联，再逻辑删除菜单；不删除 marketing_comment 业务数据或表。
UPDATE sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
SET rm.deleted = 1
WHERE rm.deleted = 0
  AND (m.code = 'marketing_comment' OR m.path = '/marketing/comment');

UPDATE sys_menu
SET deleted = 1,
    status = 0
WHERE deleted = 0
  AND (code = 'marketing_comment' OR path = '/marketing/comment');

-- ---------- 5. 退款对账缺口字段 ----------
SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'reconcile_issue'
       AND COLUMN_NAME = 'refund_no') = 0,
    'ALTER TABLE reconcile_issue ADD COLUMN refund_no VARCHAR(64) NULL COMMENT ''退款单号；用于关联退款对账缺口''',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'reconcile_issue'
       AND INDEX_NAME = 'idx_reconcile_refund') = 0,
    'ALTER TABLE reconcile_issue ADD INDEX idx_reconcile_refund (refund_no)',
    'SELECT 1'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;