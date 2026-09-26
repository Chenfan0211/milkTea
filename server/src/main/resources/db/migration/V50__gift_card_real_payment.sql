-- V50：礼品卡微信真实支付与退款数据库支线。
--
-- 本迁移只处理数据库结构、历史金额口径和历史一单一卡回填，不修改任何已执行
-- 的 V48/V49。订单后续状态流转由 Java 业务代码完成；金额统一以服务端面额表的
-- sale_price(分) 为权威来源，不能用客户端传入金额回填。
--
-- 幂等策略：
--   * 新列、唯一索引、退款表均先查 information_schema，再通过动态 SQL 执行；
--   * 不依赖 MySQL 8 不支持的 DDL 简写；
--   * 回填只处理尚未绑定卡的订单或以 order_id 为空的卡，可安全重复执行。

-- ---------------------------------------------------------------------------
-- 1. gift_card_order：支付超时时间与微信交易号
-- ---------------------------------------------------------------------------
SET @exist := (SELECT COUNT(*)
               FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'gift_card_order'
                 AND COLUMN_NAME = 'expire_time');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE gift_card_order ADD COLUMN expire_time DATETIME NULL COMMENT ''支付过期时间'' AFTER pay_status',
    'SELECT ''gift_card_order.expire_time 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*)
               FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'gift_card_order'
                 AND COLUMN_NAME = 'transaction_id');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE gift_card_order ADD COLUMN transaction_id VARCHAR(64) NULL COMMENT ''微信支付交易号'' AFTER expire_time',
    'SELECT ''gift_card_order.transaction_id 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 统一历史订单金额口径：订单 amount 保存服务端 sale_price(分)。
-- 历史数据没有价格快照表，因此按当前有效面额售价修正；只修正大于 0 的售价，
-- 避免把历史有效订单覆盖成 0。后续调用方仍必须以服务端面额值为准。
UPDATE gift_card_order o
JOIN gift_card_denomination d ON d.id = o.denomination_id
SET o.amount = d.sale_price
WHERE d.sale_price > 0
  AND o.amount <> d.sale_price
  AND o.pay_status = 'UNPAID'
  AND o.status = 'CREATED';

-- ---------------------------------------------------------------------------
-- 2. gift_card：一单一卡关联
-- ---------------------------------------------------------------------------
SET @exist := (SELECT COUNT(*)
               FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'gift_card'
                 AND COLUMN_NAME = 'order_id');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE gift_card ADD COLUMN order_id BIGINT UNSIGNED NULL COMMENT ''礼品卡订单 ID；NULL 表示历史未绑定卡'' AFTER owner_user_id',
    'SELECT ''gift_card.order_id 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- InnoDB 唯一索引允许多个 NULL，因此历史未绑定卡不会互相冲突；
-- 非空 order_id 只允许出现一次，保证一笔订单最多绑定一张卡。
SET @exist := (SELECT COUNT(*)
               FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'gift_card'
                 AND INDEX_NAME = 'uk_gift_card_order_id');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE gift_card ADD UNIQUE KEY uk_gift_card_order_id (order_id)',
    'SELECT ''uk_gift_card_order_id 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 3. 礼品卡退款记录
-- ---------------------------------------------------------------------------
SET @exist := (SELECT COUNT(*)
               FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'gift_card_refund');
SET @ddl := IF(@exist = 0,
    'CREATE TABLE gift_card_refund (
        id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
        refund_no VARCHAR(64) NOT NULL,
        order_no VARCHAR(64) NOT NULL,
        amount BIGINT NOT NULL DEFAULT 0 COMMENT ''退款金额(分)'',
        reason VARCHAR(255) NULL COMMENT ''退款原因'',
        status VARCHAR(16) NOT NULL DEFAULT ''REFUNDING'' COMMENT ''REFUNDING/FAILED/SUCCESS'',
        wx_refund_id VARCHAR(64) NULL COMMENT ''微信退款单号'',
        fail_reason VARCHAR(500) NULL COMMENT ''失败原因'',
        finished_time DATETIME NULL COMMENT ''退款完成时间'',
        create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        deleted TINYINT NOT NULL DEFAULT 0,
        PRIMARY KEY (id),
        UNIQUE KEY uk_gift_card_refund_no (refund_no),
        KEY idx_gift_card_refund_order_status (order_no, status, deleted),
        KEY idx_gift_card_refund_status_update (status, update_time, id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT=''礼品卡退款记录''',
    'SELECT ''gift_card_refund 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- 4. 历史已支付订单与卡确定性回填
-- ---------------------------------------------------------------------------
-- 订单按 user_id + denomination_id 分组，create_time/id 升序确定顺序；
-- 卡也按相同分组，ACTIVE 优先，再按 id 升序。只使用未绑定 order_id 的卡，
-- 因此重复执行不会把同一张历史卡分配给多个订单。
DROP TEMPORARY TABLE IF EXISTS tmp_v50_gift_card_orders;
CREATE TEMPORARY TABLE tmp_v50_gift_card_orders (
    order_id        BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    denomination_id BIGINT UNSIGNED NOT NULL,
    order_rank      BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB;

INSERT INTO tmp_v50_gift_card_orders (order_id, user_id, denomination_id, order_rank)
SELECT o.id,
       o.user_id,
       o.denomination_id,
       ROW_NUMBER() OVER (
           PARTITION BY o.user_id, o.denomination_id
           ORDER BY o.create_time ASC, o.id ASC
       ) AS order_rank
FROM gift_card_order o
WHERE o.deleted = 0
  AND o.pay_status = 'PAID'
  AND NOT EXISTS (
      SELECT 1
      FROM gift_card c
      WHERE c.order_id = o.id
  );

DROP TEMPORARY TABLE IF EXISTS tmp_v50_gift_card_cards;
CREATE TEMPORARY TABLE tmp_v50_gift_card_cards (
    card_id         BIGINT UNSIGNED NOT NULL,
    user_id         BIGINT UNSIGNED NOT NULL,
    denomination_id BIGINT UNSIGNED NOT NULL,
    card_rank       BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (card_id)
) ENGINE=InnoDB;

INSERT INTO tmp_v50_gift_card_cards (card_id, user_id, denomination_id, card_rank)
SELECT c.id,
       c.owner_user_id,
       c.denomination_id,
       ROW_NUMBER() OVER (
           PARTITION BY c.owner_user_id, c.denomination_id
           ORDER BY CASE WHEN c.status = 'ACTIVE' THEN 0 ELSE 1 END, c.id ASC
       ) AS card_rank
FROM gift_card c
WHERE c.deleted = 0
  AND c.owner_user_id IS NOT NULL
  AND c.order_id IS NULL;

UPDATE gift_card c
JOIN tmp_v50_gift_card_cards cc ON cc.card_id = c.id
JOIN tmp_v50_gift_card_orders oo
  ON oo.user_id = cc.user_id
 AND oo.denomination_id = cc.denomination_id
 AND oo.order_rank = cc.card_rank
SET c.order_id = oo.order_id
WHERE c.order_id IS NULL;

-- 历史已支付但完全没有可绑定卡的订单，补一张 ACTIVE 卡并绑定。
-- 卡号由订单主键确定性生成，不使用不存在的数据库序列。
INSERT INTO gift_card (
    card_no,
    denomination_id,
    status,
    owner_user_id,
    order_id,
    create_time,
    update_time,
    deleted
)
SELECT CONCAT('CARD-MIG-', LPAD(o.id, 20, '0')),
       o.denomination_id,
       'ACTIVE',
       o.user_id,
       o.id,
       COALESCE(o.pay_time, o.create_time, CURRENT_TIMESTAMP),
       COALESCE(o.pay_time, o.create_time, CURRENT_TIMESTAMP),
       0
FROM gift_card_order o
WHERE o.deleted = 0
  AND o.pay_status = 'PAID'
  AND NOT EXISTS (
      SELECT 1
      FROM gift_card c
      WHERE c.order_id = o.id
  );

DROP TEMPORARY TABLE IF EXISTS tmp_v50_gift_card_cards;
DROP TEMPORARY TABLE IF EXISTS tmp_v50_gift_card_orders;
