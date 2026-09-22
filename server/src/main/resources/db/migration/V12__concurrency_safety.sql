-- =============================================================
-- 第 0 期：并发安全加固（V12）
-- 背景：资金/库存存在读-改-写竞态，详见 docs/微服务改造方案.md 第四章
-- 原则：数据库唯一约束作为最后一道兜底，应用层改为原子 SQL
--
-- 编写前已核对 V1/V2 现状：
--   orders(order_no)               已有 uk_orders_order_no          -> 无需改
--   withdrawal(withdraw_no)        已有 uk_withdrawal_no            -> 无需改
--   payment(payment_no)            已有 uk_payment_no               -> 无需改
--   subject_account(subject_id)    已有 uk_subject_account_subject  -> 无需改
--   points_signin(user_id,sign_date) 已有 uk_points_signin          -> 无需改
--   split_snapshot(order_id)       仅普通索引 idx_split_snapshot_order -> 本次修复
-- =============================================================

-- ---------- 1. 分账快照：同一订单只能有一份（防重复分账） ----------
-- 并发核销同一订单时，「先查快照再插入」存在竞态窗口，会生成多条快照导致重复分账。
-- 步骤：先清理历史重复（每组 order_id 仅保留 id 最小的一条），再升级为唯一索引。
-- 1.1 清理历史重复（每组 order_id 仅保留 id 最小的一条）
DELETE s1 FROM split_snapshot s1
    INNER JOIN split_snapshot s2
        ON s1.order_id = s2.order_id AND s1.id > s2.id;

-- 1.2 删除旧的普通索引（若存在）
-- 说明：MySQL 8 无 DROP INDEX IF EXISTS，用存储过程按 information_schema 判断，
--       保证迁移在「索引已存在/已删除」两种状态下都能重复执行。
DROP PROCEDURE IF EXISTS wuling_drop_index_if_exists;
DELIMITER $$
CREATE PROCEDURE wuling_drop_index_if_exists()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'split_snapshot'
                 AND index_name = 'idx_split_snapshot_order') THEN
        ALTER TABLE split_snapshot DROP INDEX idx_split_snapshot_order;
    END IF;
END$$
DELIMITER ;
CALL wuling_drop_index_if_exists();
DROP PROCEDURE wuling_drop_index_if_exists;

-- 1.3 建立唯一索引（若已存在则跳过）
DROP PROCEDURE IF EXISTS wuling_add_unique_order_index;
DELIMITER $$
CREATE PROCEDURE wuling_add_unique_order_index()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'split_snapshot'
                     AND index_name = 'uk_split_snapshot_order') THEN
        ALTER TABLE split_snapshot ADD UNIQUE KEY uk_split_snapshot_order (order_id);
    END IF;
END$$
DELIMITER ;
CALL wuling_add_unique_order_index();
DROP PROCEDURE wuling_add_unique_order_index;

-- ---------- 2. 用户优惠券：同一用户同一券模板只允许持有一张 ----------
-- 原「先查 owned 再插入」在并发下可重复领取，绕过库存扣减。
-- 用生成列把「有效持有」归一为可唯一索引的列：
-- 已用/过期/退回的记录置 NULL，MySQL 唯一索引允许多个 NULL，故不影响历史记录。
DROP PROCEDURE IF EXISTS wuling_add_user_coupon_active;
DELIMITER $$
CREATE PROCEDURE wuling_add_user_coupon_active()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = DATABASE()
                     AND table_name = 'user_coupon'
                     AND column_name = 'active_coupon_id') THEN
        ALTER TABLE user_coupon
            ADD COLUMN active_coupon_id BIGINT UNSIGNED
                GENERATED ALWAYS AS (
                    CASE WHEN deleted = 0 AND status IN ('UNUSED', 'LOCKED')
                         THEN coupon_id ELSE NULL END
                ) STORED COMMENT '有效持有券模板ID（并发唯一约束用）';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'user_coupon'
                     AND index_name = 'uk_user_coupon_active') THEN
        ALTER TABLE user_coupon ADD UNIQUE KEY uk_user_coupon_active (user_id, active_coupon_id);
    END IF;
END$$
DELIMITER ;
CALL wuling_add_user_coupon_active();
DROP PROCEDURE wuling_add_user_coupon_active;

-- ---------- 3. CHECK 约束：金额/库存不得为负（原子 SQL 之外的兜底） ----------
-- MySQL 8.0.16+ 支持 CHECK 约束，作为「超发/超提」的最后一道防线。

DROP PROCEDURE IF EXISTS wuling_add_check_constraints;
DELIMITER $$
CREATE PROCEDURE wuling_add_check_constraints()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND constraint_name = 'ck_coupon_stock_non_negative') THEN
        ALTER TABLE coupon
            ADD CONSTRAINT ck_coupon_stock_non_negative CHECK (stock IS NULL OR stock >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND constraint_name = 'ck_points_product_stock_non_negative') THEN
        ALTER TABLE points_product
            ADD CONSTRAINT ck_points_product_stock_non_negative CHECK (stock >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND constraint_name = 'ck_subject_account_available_non_negative') THEN
        ALTER TABLE subject_account
            ADD CONSTRAINT ck_subject_account_available_non_negative CHECK (available_balance >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND constraint_name = 'ck_subject_account_frozen_non_negative') THEN
        ALTER TABLE subject_account
            ADD CONSTRAINT ck_subject_account_frozen_non_negative CHECK (frozen_balance >= 0);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND constraint_name = 'ck_app_user_balance_non_negative') THEN
        ALTER TABLE app_user
            ADD CONSTRAINT ck_app_user_balance_non_negative CHECK (balance >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND constraint_name = 'ck_app_user_points_non_negative') THEN
        ALTER TABLE app_user
            ADD CONSTRAINT ck_app_user_points_non_negative CHECK (points >= 0);
    END IF;
END$$
DELIMITER ;
CALL wuling_add_check_constraints();
DROP PROCEDURE wuling_add_check_constraints;
