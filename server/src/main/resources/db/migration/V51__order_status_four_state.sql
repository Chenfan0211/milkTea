-- V51：订单状态四态收敛与 CHECK 约束。
--
-- 目标：
--   orders.status / gift_card_order.status 最终只允许
--   CREATED / PAID / COMPLETED / CANCELED。
--
-- 幂等策略：
--   * 先回填历史状态，再添加 CHECK 约束；
--   * CHECK 约束按 information_schema.table_constraints 判断存在性；
--   * 使用与 V50 相同的 SET/PREPARE/EXECUTE/DEALLOCATE 动态 DDL 风格；
--   * 不写 Flyway 历史表，可安全重复执行。

-- ---------------------------------------------------------------------------
-- 1. orders 历史状态回填
-- ---------------------------------------------------------------------------
UPDATE orders
SET status = 'COMPLETED'
WHERE status = 'VERIFIED';

UPDATE orders
SET status = 'CANCELED',
    refund_status = 'REFUNDED'
WHERE status = 'REFUNDED';

-- ---------------------------------------------------------------------------
-- 2. gift_card_order 历史状态回填
-- ---------------------------------------------------------------------------
UPDATE gift_card_order
SET status = 'COMPLETED'
WHERE status = 'VERIFIED';

UPDATE gift_card_order
SET status = 'PAID'
WHERE status = 'REFUNDING';

-- ---------------------------------------------------------------------------
-- 3. orders 四态 CHECK 约束
-- ---------------------------------------------------------------------------
SET @exist := (SELECT COUNT(*)
               FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'orders'
                 AND CONSTRAINT_NAME = 'chk_orders_status_four_state');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE orders ADD CONSTRAINT chk_orders_status_four_state CHECK (status IN (''CREATED'', ''PAID'', ''COMPLETED'', ''CANCELED''))',
    'SELECT ''chk_orders_status_four_state 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4. gift_card_order 四态 CHECK 约束
-- ---------------------------------------------------------------------------
SET @exist := (SELECT COUNT(*)
               FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'gift_card_order'
                 AND CONSTRAINT_NAME = 'chk_gift_card_order_status_four_state');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE gift_card_order ADD CONSTRAINT chk_gift_card_order_status_four_state CHECK (status IN (''CREATED'', ''PAID'', ''COMPLETED'', ''CANCELED''))',
    'SELECT ''chk_gift_card_order_status_four_state 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
