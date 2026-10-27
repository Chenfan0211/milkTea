-- =====================================================================
-- V86: 小程序商城
--
-- 设计要点：
--   * 商城商品独立于门店饮品菜单，避免污染 product/product_spec；
--   * 订单复用 orders/order_item，通过 order_type 严格分派履约流程；
--   * SKU 采用 stock + locked_stock，库存预占与支付扣减均走行锁；
--   * 地址、订单收货快照、库存流水均保留审计信息。
-- =====================================================================

CREATE TABLE IF NOT EXISTS mall_category (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    sort        INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mall_category_code (code),
    KEY idx_mall_category_sort (status, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城分类';

CREATE TABLE IF NOT EXISTS mall_product (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_code                VARCHAR(64) NOT NULL,
    category_id                 BIGINT UNSIGNED NOT NULL,
    name                        VARCHAR(128) NOT NULL,
    subtitle                    VARCHAR(255) NULL,
    spec_name                   VARCHAR(64) NOT NULL DEFAULT '规格',
    cover_image                 VARCHAR(255) NULL,
    gallery_images              JSON NULL,
    description                 TEXT NULL,
    attribution_store_subject_id BIGINT UNSIGNED NOT NULL,
    attribution_channel_subject_id BIGINT UNSIGNED NULL,
    split_rule_id               BIGINT UNSIGNED NULL,
    status                      VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ON_SALE/OFF_SALE',
    sort                        INT NOT NULL DEFAULT 0,
    sales_count                 INT NOT NULL DEFAULT 0,
    create_time                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                 DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                     TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mall_product_code (product_code),
    KEY idx_mall_product_category (category_id, status, sort),
    KEY idx_mall_product_store (attribution_store_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城商品';

CREATE TABLE IF NOT EXISTS mall_sku (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    product_id           BIGINT UNSIGNED NOT NULL,
    sku_code             VARCHAR(64) NOT NULL,
    spec_value           VARCHAR(64) NOT NULL,
    image                VARCHAR(255) NULL,
    sale_price           BIGINT NOT NULL DEFAULT 0,
    original_price       BIGINT NOT NULL DEFAULT 0,
    cost_price           BIGINT NOT NULL DEFAULT 0,
    platform_commission  BIGINT NOT NULL DEFAULT 0,
    supplier_subject_id  BIGINT UNSIGNED NOT NULL,
    stock                INT NOT NULL DEFAULT 0,
    locked_stock         INT NOT NULL DEFAULT 0,
    sort                 INT NOT NULL DEFAULT 0,
    version              INT NOT NULL DEFAULT 0,
    create_time          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted              TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mall_sku_code (sku_code),
    KEY idx_mall_sku_product (product_id, deleted, sort),
    KEY idx_mall_sku_supplier (supplier_subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城SKU';

CREATE TABLE IF NOT EXISTS mall_stock_log (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    sku_id       BIGINT UNSIGNED NOT NULL,
    order_no     VARCHAR(64) NULL,
    change_type  VARCHAR(16) NOT NULL COMMENT 'RESERVE/RELEASE/DEDUCT/RESTORE/ADJUST',
    stock_delta  INT NOT NULL DEFAULT 0,
    locked_delta INT NOT NULL DEFAULT 0,
    stock_after  INT NOT NULL,
    locked_after INT NOT NULL,
    operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    operator_id  VARCHAR(64) NULL,
    remark       VARCHAR(255) NULL,
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_mall_stock_log_sku (sku_id, create_time),
    KEY idx_mall_stock_log_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城库存流水';

CREATE TABLE IF NOT EXISTS app_user_address (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id       BIGINT UNSIGNED NOT NULL,
    recipient_name VARCHAR(32) NOT NULL,
    phone         VARCHAR(20) NOT NULL,
    province_code VARCHAR(32) NULL,
    province_name VARCHAR(64) NOT NULL,
    city_code     VARCHAR(32) NULL,
    city_name     VARCHAR(64) NOT NULL,
    district_code VARCHAR(32) NULL,
    district_name VARCHAR(64) NOT NULL,
    detail_address VARCHAR(255) NOT NULL,
    tag           VARCHAR(16) NULL,
    is_default    TINYINT NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_app_user_address_user (user_id, deleted, is_default, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户收货地址';

CREATE TABLE IF NOT EXISTS order_shipping (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    order_id         BIGINT UNSIGNED NOT NULL,
    recipient_name   VARCHAR(32) NOT NULL,
    phone            VARCHAR(20) NOT NULL,
    province_name    VARCHAR(64) NOT NULL,
    city_name        VARCHAR(64) NOT NULL,
    district_name    VARCHAR(64) NOT NULL,
    detail_address   VARCHAR(255) NOT NULL,
    courier_company  VARCHAR(64) NULL,
    tracking_no      VARCHAR(64) NULL,
    create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted          TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_shipping_order (order_id),
    KEY idx_order_shipping_tracking (tracking_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商城订单收货与物流快照';

-- ---------- orders: 订单类型、分账规则快照、发货时间 ----------
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'order_type') = 0,
    'ALTER TABLE orders ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT ''STORE'' COMMENT ''STORE/MALL'' AFTER order_no',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'split_rule_id') = 0,
    'ALTER TABLE orders ADD COLUMN split_rule_id BIGINT UNSIGNED NULL COMMENT ''下单时分账规则快照'' AFTER store_subject_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'client_request_id') = 0,
    'ALTER TABLE orders ADD COLUMN client_request_id VARCHAR(64) NULL COMMENT ''客户端下单幂等键'' AFTER order_no',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'uk_orders_user_request') = 0,
    'ALTER TABLE orders ADD UNIQUE KEY uk_orders_user_request (user_id, client_request_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'split_rule_snapshot') = 0,
    'ALTER TABLE orders ADD COLUMN split_rule_snapshot JSON NULL COMMENT ''下单时不可变分账规则快照'' AFTER split_rule_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'shipped_time') = 0,
    'ALTER TABLE orders ADD COLUMN shipped_time DATETIME NULL COMMENT ''商城发货时间'' AFTER verify_time',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'auto_complete_time') = 0,
    'ALTER TABLE orders ADD COLUMN auto_complete_time DATETIME NULL COMMENT ''商城自动完成时间'' AFTER shipped_time',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_type_status') = 0,
    'ALTER TABLE orders ADD KEY idx_orders_type_status (order_type, status, auto_complete_time)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- order_item: 商城快照字段 ----------
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'mall_sku_id') = 0,
    'ALTER TABLE order_item ADD COLUMN mall_sku_id BIGINT UNSIGNED NULL COMMENT ''商城SKU ID'' AFTER order_id',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'cost_price') = 0,
    'ALTER TABLE order_item ADD COLUMN cost_price BIGINT NULL COMMENT ''成本价快照(分/件)'' AFTER unit_price',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'platform_commission') = 0,
    'ALTER TABLE order_item ADD COLUMN platform_commission BIGINT NULL COMMENT ''平台提成快照(分/件)'' AFTER cost_price',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'supplier_subject_id') = 0,
    'ALTER TABLE order_item ADD COLUMN supplier_subject_id BIGINT UNSIGNED NULL COMMENT ''供应商主体快照'' AFTER platform_commission',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 五态订单约束：保留原有四态并增加 SHIPPED。
SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
                 AND CONSTRAINT_NAME = 'chk_orders_status_four_state');
SET @ddl := IF(@exist > 0,
    'ALTER TABLE orders DROP CHECK chk_orders_status_four_state',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders'
                 AND CONSTRAINT_NAME = 'chk_orders_status_mall');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE orders ADD CONSTRAINT chk_orders_status_mall CHECK (status IN (''CREATED'', ''PAID'', ''SHIPPED'', ''COMPLETED'', ''CANCELED''))',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 我的页入口 ----------
UPDATE app_config
   SET value = JSON_ARRAY_APPEND(
       value, '$',
       JSON_OBJECT('id', 'mall', 'label', '线上商城', 'icon', '/assets/icons/lucide/shopping-bag.svg')
   )
 WHERE config_key = 'profile_functions' AND deleted = 0
   AND NOT JSON_CONTAINS(value, JSON_OBJECT('id', 'mall'));

-- 商城发货订阅模板 ID 属公开配置，供小程序在结算页申请订阅授权；
-- 模板未申请时保持空字符串，H5 与后端都会静默跳过通知。
INSERT INTO app_config (config_key, config_name, value, sort, remark)
VALUES ('mall_shipped_template_id', '商城发货订阅模板ID', '"bxgB0GhwNBcC37qRILiRKOx5rxJWn7Phuw5ZaNB8lEI"', 6, '小程序发货通知模板ID')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), sort=VALUES(sort), remark=VALUES(remark);

-- ---------- 运营后台菜单 ----------
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
VALUES ('mall', 0, '商城管理', '/mall', 'layout.base', 'mdi:shopping-outline', 35, 'dir', 1, NULL)
ON DUPLICATE KEY UPDATE parent_id=0, name=VALUES(name), path=VALUES(path), component=VALUES(component),
    icon=VALUES(icon), order_num=VALUES(order_num), type='dir', status=1, deleted=0;

INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'mall_category', p.id, '商城分类', '/mall/category', 'view.mall_category', 'mdi:shape-outline', 1, 'menu', 1, NULL
FROM sys_menu p WHERE p.code = 'mall' AND p.deleted = 0
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), path=VALUES(path), component=VALUES(component),
    icon=VALUES(icon), order_num=VALUES(order_num), type='menu', status=1, deleted=0;

INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'mall_product', p.id, '商城商品', '/mall/product', 'view.mall_product', 'mdi:package-variant-closed', 2, 'menu', 1, NULL
FROM sys_menu p WHERE p.code = 'mall' AND p.deleted = 0
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), path=VALUES(path), component=VALUES(component),
    icon=VALUES(icon), order_num=VALUES(order_num), type='menu', status=1, deleted=0;

INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'mall_order', p.id, '商城订单', '/mall/order', 'view.mall_order', 'mdi:truck-delivery-outline', 3, 'menu', 1, NULL
FROM sys_menu p WHERE p.code = 'mall' AND p.deleted = 0
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), path=VALUES(path), component=VALUES(component),
    icon=VALUES(icon), order_num=VALUES(order_num), type='menu', status=1, deleted=0;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0 AND m.code IN ('mall', 'mall_category', 'mall_product', 'mall_order')
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, update_time = CURRENT_TIMESTAMP;

-- 商城商品至少需要一组演示分类，便于后台首次进入即可建品。
INSERT INTO mall_category (code, name, sort, status)
VALUES ('tea-goods', '茶器好物', 10, 'ENABLED')
ON DUPLICATE KEY UPDATE name=VALUES(name), sort=VALUES(sort), status=VALUES(status), deleted=0;
