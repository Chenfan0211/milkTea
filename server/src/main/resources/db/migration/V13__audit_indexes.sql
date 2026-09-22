-- =============================================================
-- P2 安全加固：审计日志查询索引
-- 背景：audit_log 表原有 idx_audit_log_create_time，
--       但后台按 module/action/operator 过滤时无合适索引，
--       数据量增长后审计查询会全表扫描。
-- 幂等：通过 information_schema 判断后再创建。
-- =============================================================

DROP PROCEDURE IF EXISTS wuling_add_audit_indexes;
DELIMITER $$
CREATE PROCEDURE wuling_add_audit_indexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'audit_log'
                     AND index_name = 'idx_audit_log_module_action') THEN
        ALTER TABLE audit_log ADD INDEX idx_audit_log_module_action (module, action);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'audit_log'
                     AND index_name = 'idx_audit_log_operator') THEN
        ALTER TABLE audit_log ADD INDEX idx_audit_log_operator (operator);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'audit_log'
                     AND index_name = 'idx_audit_log_target') THEN
        ALTER TABLE audit_log ADD INDEX idx_audit_log_target (target);
    END IF;
END$$
DELIMITER ;
CALL wuling_add_audit_indexes();
DROP PROCEDURE wuling_add_audit_indexes;
