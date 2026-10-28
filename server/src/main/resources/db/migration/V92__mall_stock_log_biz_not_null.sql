-- =====================================================================
-- V92: 商城库存流水业务幂等键约束收紧
--
-- 背景：V89 引入 biz_type + biz_no 作为业务幂等键，但两列允许 NULL。
-- MySQL 唯一索引不去重 NULL，因此：
--   1. 历史 ADJUST（后台库存调整）记录 biz_type/biz_no 为空，索引对其无效；
--   2. 任何写入侧遗漏都会静默产生重复流水。
--
-- 本迁移：
--   1. 回填历史空值（ADJUST 生成唯一审计号，其余按 ORDER 口径回填）；
--   2. 将 biz_type / biz_no 收紧为 NOT NULL，从表结构上杜绝再次为空；
--   3. 增加 biz_type 取值约束，避免出现拼写不一致的新类型。
-- =====================================================================

-- ---------- 1. 回填 biz_type ----------
-- 有订单号的按订单口径回填（V89 已做，此处兜底历史遗漏）
UPDATE mall_stock_log
SET biz_type = 'ORDER'
WHERE biz_type IS NULL AND order_no IS NOT NULL;

-- 无订单号的库存调整为人工审计，单独归为 ADJUST
UPDATE mall_stock_log
SET biz_type = 'ADJUST'
WHERE biz_type IS NULL;

-- ---------- 2. 回填 biz_no ----------
-- 有订单号的用订单号
UPDATE mall_stock_log
SET biz_no = order_no
WHERE biz_no IS NULL AND order_no IS NOT NULL;

-- 库存调整无业务单号：用主键生成稳定唯一值，保证可追溯且不冲突
UPDATE mall_stock_log
SET biz_no = CONCAT('ADJ-LEGACY-', id)
WHERE biz_no IS NULL;

-- ---------- 3. 收紧为 NOT NULL ----------
ALTER TABLE mall_stock_log
    MODIFY COLUMN biz_type VARCHAR(16) NOT NULL COMMENT '业务类型：ORDER/AFTER_SALE/ADJUST',
    MODIFY COLUMN biz_no   VARCHAR(64) NOT NULL COMMENT '业务幂等号：订单号/售后单号/调整审计号';

-- ---------- 4. biz_type 取值约束 ----------
SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_stock_log'
                 AND CONSTRAINT_NAME = 'ck_mall_stock_log_biz_type');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE mall_stock_log ADD CONSTRAINT ck_mall_stock_log_biz_type CHECK (biz_type IN (''ORDER'',''AFTER_SALE'',''ADJUST''))',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
