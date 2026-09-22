-- =============================================================
-- 第 8 期：资金对账索引
--
-- 背景：reconcile_issue 表在 V1 已建，但只有 idx_reconcile_order。
-- 对账任务的两个高频查询需要更贴合的索引：
--   1. recordOnce 去重：where issue_type=? and order_no=? and status=?
--   2. openIssues 列表：where status=?
-- 幂等：通过 information_schema 判断后再创建。
-- =============================================================

DROP PROCEDURE IF EXISTS wuling_add_reconcile_indexes;
DELIMITER $$
CREATE PROCEDURE wuling_add_reconcile_indexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'reconcile_issue'
                     AND index_name = 'idx_reconcile_type_order_status') THEN
        ALTER TABLE reconcile_issue
            ADD INDEX idx_reconcile_type_order_status (issue_type, order_no, status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'reconcile_issue'
                     AND index_name = 'idx_reconcile_status') THEN
        ALTER TABLE reconcile_issue ADD INDEX idx_reconcile_status (status);
    END IF;
END$$
DELIMITER ;
CALL wuling_add_reconcile_indexes();
DROP PROCEDURE wuling_add_reconcile_indexes;
