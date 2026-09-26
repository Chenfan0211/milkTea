-- =====================================================================
-- V55：储值余额支付、优惠券字段与共享 outbox 基础设施
--
-- 本迁移只增加向前兼容的列、表和索引，不修改订单/优惠券业务逻辑。
-- 所有 DDL 均先查询 information_schema，可重复执行。
-- =====================================================================

-- ---------- 1. orders：支付渠道与储值立减快照 ----------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'orders'
                 AND COLUMN_NAME = 'pay_channel');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE orders ADD COLUMN pay_channel VARCHAR(16) NOT NULL DEFAULT ''WXPAY'' COMMENT ''支付渠道：WXPAY/STORED_VALUE'' AFTER pay_status',
    'SELECT ''orders.pay_channel 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'orders'
                 AND COLUMN_NAME = 'stored_value_discount');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE orders ADD COLUMN stored_value_discount BIGINT NOT NULL DEFAULT 0 COMMENT ''储值渠道立减金额（分）'' AFTER coupon_discount',
    'SELECT ''orders.stored_value_discount 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'orders'
                 AND INDEX_NAME = 'idx_orders_pay_channel');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE orders ADD INDEX idx_orders_pay_channel (pay_channel, pay_status)',
    'SELECT ''idx_orders_pay_channel 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. stored_value_txn：储值扣款/退款业务幂等表 ----------
CREATE TABLE IF NOT EXISTS stored_value_txn (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    biz_no        VARCHAR(128) NOT NULL COMMENT '稳定业务号，扣款/退款幂等键',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    order_no      VARCHAR(64) NOT NULL COMMENT '订单号',
    operation_type VARCHAR(16) NOT NULL COMMENT 'PAY/REFUND/COMPENSATE',
    amount        BIGINT NOT NULL DEFAULT 0 COMMENT '金额（分，正数）',
    status        VARCHAR(16) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/FAILED',
    request_hash  VARCHAR(128) NULL COMMENT '请求摘要，用于冲突检测',
    result_message VARCHAR(500) NULL COMMENT '处理结果或失败原因',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_value_txn_biz_no (biz_no),
    KEY idx_stored_value_txn_order (order_no, operation_type),
    KEY idx_stored_value_txn_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='储值资金业务幂等记录';

-- ---------- 3. event_outbox：共享库事务内 outbox ----------
CREATE TABLE IF NOT EXISTS event_outbox (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(64) NOT NULL COMMENT '事件唯一ID',
    aggregate_type  VARCHAR(64) NOT NULL COMMENT '聚合类型',
    aggregate_id    VARCHAR(128) NOT NULL COMMENT '聚合ID',
    event_type      VARCHAR(64) NOT NULL COMMENT '事件类型',
    routing_key     VARCHAR(128) NOT NULL COMMENT 'RabbitMQ 路由键',
    biz_key         VARCHAR(128) NULL COMMENT '业务追踪键，如订单号',
    payload         JSON NOT NULL COMMENT '事件负载',
    status          VARCHAR(16) NOT NULL DEFAULT 'NEW' COMMENT 'NEW/PUBLISHING/SENT/FAILED',
    retry_count     INT NOT NULL DEFAULT 0 COMMENT '已失败次数',
    next_retry_at   DATETIME NULL COMMENT '下一次可重试时间',
    locked_by       VARCHAR(128) NULL COMMENT '认领者',
    locked_at       DATETIME NULL COMMENT '认领时间',
    last_error      VARCHAR(1000) NULL COMMENT '最近失败原因',
    available_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最早可发布时间',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at         DATETIME NULL COMMENT 'Broker确认时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_outbox_event_id (event_id),
    KEY idx_event_outbox_claim (status, available_at, next_retry_at, id),
    KEY idx_event_outbox_locked (status, locked_at, id),
    KEY idx_event_outbox_biz (aggregate_type, aggregate_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='事务内共享事件 outbox';

-- 兼容已存在但由早期版本创建、缺少索引的表结构。
SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'event_outbox'
                 AND INDEX_NAME = 'idx_event_outbox_claim');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE event_outbox ADD INDEX idx_event_outbox_claim (status, available_at, next_retry_at, id)',
    'SELECT ''idx_event_outbox_claim 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'event_outbox'
                 AND INDEX_NAME = 'idx_event_outbox_locked');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE event_outbox ADD INDEX idx_event_outbox_locked (status, locked_at, id)',
    'SELECT ''idx_event_outbox_locked 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;