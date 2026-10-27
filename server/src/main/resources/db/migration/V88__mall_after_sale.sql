-- =====================================================================
-- V88: 商城订单售后（退货退款）
--
-- 变更点：
--   1. mall_after_sale：用户售后单（退货退款申请），支持部分/整单退货；
--   2. refund.after_sale_id：退款单与售后单关联（可空，兼容既有整单退款）；
--   3. orders 状态约束扩展 REFUNDED（整单退完才置该状态，部分退保持原状态）；
--   4. refund.original_amount：退款申请时的订单实付快照（用于比例冲正兜底）。
-- =====================================================================

-- ---------- 1. 售后单 ----------
CREATE TABLE IF NOT EXISTS mall_after_sale (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    after_sale_no  VARCHAR(64) NOT NULL,
    order_id       BIGINT UNSIGNED NOT NULL,
    order_no       VARCHAR(64) NOT NULL,
    user_id        BIGINT UNSIGNED NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING/APPROVED/REJECTED/REFUNDING/SUCCESS/FAILED',
    reason         VARCHAR(255) NULL,
    refund_amount  BIGINT NOT NULL DEFAULT 0 COMMENT '退款金额(分)',
    items          JSON NULL COMMENT '退货明细:[{orderItemId,skuId,skuCode,name,spec,quantity,unitPrice,distributionCommission,lineAmount}]',
    apply_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    review_time    DATETIME NULL,
    reviewer       VARCHAR(64) NULL,
    complete_time  DATETIME NULL,
    refund_id      BIGINT UNSIGNED NULL COMMENT '关联退款单ID（审核通过创建退款单后回填）',
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mall_after_sale_no (after_sale_no),
    KEY idx_mall_after_sale_order (order_id),
    KEY idx_mall_after_sale_user (user_id, status),
    KEY idx_mall_after_sale_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城售后单(退货退款)';

-- ---------- 2. refund 关联售后单与订单实付快照 ----------
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund' AND COLUMN_NAME = 'after_sale_id') = 0,
    'ALTER TABLE refund ADD COLUMN after_sale_id BIGINT UNSIGNED NULL COMMENT ''关联售后单ID'' AFTER order_no',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund' AND COLUMN_NAME = 'original_amount') = 0,
    'ALTER TABLE refund ADD COLUMN original_amount BIGINT NULL COMMENT ''订单实付快照(分)，比例冲正用'' AFTER amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. orders 状态约束扩展 REFUNDED ----------
-- 整单退完置 REFUNDED；部分退保持 SHIPPED/COMPLETED 原状态（refundStatus=REFUNDED）。
SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
                 AND CONSTRAINT_NAME = 'chk_orders_status_mall');
SET @ddl := IF(@exist > 0,
    'ALTER TABLE orders DROP CHECK chk_orders_status_mall',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
                 AND CONSTRAINT_NAME = 'chk_orders_status_mall');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE orders ADD CONSTRAINT chk_orders_status_mall CHECK (status IN (''CREATED'', ''PAID'', ''SHIPPED'', ''COMPLETED'', ''CANCELED'', ''REFUNDED''))',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
