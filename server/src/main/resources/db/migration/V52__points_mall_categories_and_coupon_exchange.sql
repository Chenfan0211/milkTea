-- =====================================================================
-- V52：积分商城分类与优惠券兑换
--
-- 目标：
--   1) 分类从商品字段内嵌值升级为独立、可管理的 points_category；
--   2) 优惠券分类商品绑定 coupon 模板，兑换后直接发放用户券；
--   3) 兑换单记录 quantity，为普通商品多件兑换和限购累计提供数据基础；
--   4) 保留普通领券的防重复约束，同时允许积分兑换多张同款券。
--
-- 幂等原则：所有 DDL 先查 information_schema，再通过临时存储过程执行；
--           种子数据只按业务唯一键更新，不手工写 Flyway 历史。
-- =====================================================================

-- ---------- 1. 积分商城分类 ----------
DROP PROCEDURE IF EXISTS wuling_v52_create_points_category;
DELIMITER $$
CREATE PROCEDURE wuling_v52_create_points_category()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = 'points_category'
    ) THEN
        CREATE TABLE points_category (
            id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
            code          VARCHAR(64) NOT NULL COMMENT '分类编码',
            name          VARCHAR(64) NOT NULL COMMENT '分类名称',
            sort          INT NOT NULL DEFAULT 0 COMMENT '排序值',
            enabled       TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
            system_locked TINYINT NOT NULL DEFAULT 0 COMMENT '1系统分类，禁止改码/删除',
            create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            deleted       TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            UNIQUE KEY uk_points_category_code (code),
            KEY idx_points_category_sort (sort)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='积分商城分类';
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_create_points_category();
DROP PROCEDURE wuling_v52_create_points_category;

-- coupon 为系统锁定分类；pet 保留为普通可管理分类。
INSERT INTO points_category (code, name, sort, enabled, system_locked)
VALUES
    ('pet', '宠物公益专区', 10, 1, 0),
    ('coupon', '优惠券区', 20, 1, 1)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    sort = VALUES(sort),
    enabled = VALUES(enabled),
    system_locked = VALUES(system_locked),
    deleted = 0;

-- ---------- 2. points_product.coupon_id ----------
DROP PROCEDURE IF EXISTS wuling_v52_add_product_coupon_id;
DELIMITER $$
CREATE PROCEDURE wuling_v52_add_product_coupon_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'points_product'
          AND column_name = 'coupon_id'
    ) THEN
        ALTER TABLE points_product
            ADD COLUMN coupon_id BIGINT UNSIGNED NULL COMMENT '绑定优惠券模板ID' AFTER category;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'points_product'
          AND index_name = 'idx_points_product_coupon'
    ) THEN
        ALTER TABLE points_product
            ADD KEY idx_points_product_coupon (coupon_id);
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_add_product_coupon_id();
DROP PROCEDURE wuling_v52_add_product_coupon_id;

-- ---------- 3. exchange_order.quantity ----------
DROP PROCEDURE IF EXISTS wuling_v52_add_exchange_quantity;
DELIMITER $$
CREATE PROCEDURE wuling_v52_add_exchange_quantity()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'exchange_order'
          AND column_name = 'quantity'
    ) THEN
        ALTER TABLE exchange_order
            ADD COLUMN quantity INT NOT NULL DEFAULT 1 COMMENT '兑换数量' AFTER points;
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_add_exchange_quantity();
DROP PROCEDURE wuling_v52_add_exchange_quantity;

-- ---------- 4. user_coupon.source ----------
DROP PROCEDURE IF EXISTS wuling_v52_add_user_coupon_source;
DELIMITER $$
CREATE PROCEDURE wuling_v52_add_user_coupon_source()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'user_coupon'
          AND column_name = 'source'
    ) THEN
        ALTER TABLE user_coupon
            ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'RECEIVE'
                COMMENT '来源：RECEIVE领券/POINTS_EXCHANGE积分兑换' AFTER status;
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_add_user_coupon_source();
DROP PROCEDURE wuling_v52_add_user_coupon_source;

-- 必须先回填历史数据，再重建生成列；否则旧生成列会暂时失去有效状态判断。
UPDATE user_coupon
SET source = 'RECEIVE'
WHERE source IS NULL OR source = '';

-- 旧索引依赖 active_coupon_id，必须先删索引，再删列。
DROP PROCEDURE IF EXISTS wuling_v52_drop_user_coupon_active_index;
DELIMITER $$
CREATE PROCEDURE wuling_v52_drop_user_coupon_active_index()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'user_coupon'
          AND index_name = 'uk_user_coupon_active'
    ) THEN
        ALTER TABLE user_coupon DROP INDEX uk_user_coupon_active;
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_drop_user_coupon_active_index();
DROP PROCEDURE wuling_v52_drop_user_coupon_active_index;

DROP PROCEDURE IF EXISTS wuling_v52_drop_old_user_coupon_active_column;
DELIMITER $$
CREATE PROCEDURE wuling_v52_drop_old_user_coupon_active_column()
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'user_coupon'
          AND column_name = 'active_coupon_id'
    ) THEN
        ALTER TABLE user_coupon DROP COLUMN active_coupon_id;
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_drop_old_user_coupon_active_column();
DROP PROCEDURE wuling_v52_drop_old_user_coupon_active_column;

-- 仅普通领券按用户和券模板防重复；积分兑换来源不受该唯一约束限制。
DROP PROCEDURE IF EXISTS wuling_v52_add_receive_only_active_column;
DELIMITER $$
CREATE PROCEDURE wuling_v52_add_receive_only_active_column()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'user_coupon'
          AND column_name = 'active_coupon_id'
    ) THEN
        ALTER TABLE user_coupon
            ADD COLUMN active_coupon_id BIGINT UNSIGNED
                GENERATED ALWAYS AS (
                    CASE WHEN deleted = 0 AND status IN ('UNUSED', 'LOCKED') AND source = 'RECEIVE'
                         THEN coupon_id ELSE NULL END
                ) STORED COMMENT '普通领券有效持有券模板ID（并发唯一约束用）';
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_add_receive_only_active_column();
DROP PROCEDURE wuling_v52_add_receive_only_active_column;

DROP PROCEDURE IF EXISTS wuling_v52_add_receive_only_active_index;
DELIMITER $$
CREATE PROCEDURE wuling_v52_add_receive_only_active_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'user_coupon'
          AND index_name = 'uk_user_coupon_active'
    ) THEN
        ALTER TABLE user_coupon
            ADD UNIQUE KEY uk_user_coupon_active (user_id, active_coupon_id);
    END IF;
END$$
DELIMITER ;
CALL wuling_v52_add_receive_only_active_index();
DROP PROCEDURE wuling_v52_add_receive_only_active_index;

-- ---------- 5. 历史积分商品回填 ----------
-- 现网只有 3 元券模板 coupon-001 能准确匹配这两件历史商品。
UPDATE points_product p
JOIN coupon c ON c.code = 'coupon-001' AND c.deleted = 0
SET p.coupon_id = c.id
WHERE p.deleted = 0
  AND p.code IN ('points-coupon-3', 'points-single-cup');

-- 买一送一/第二杯半价没有可一一对应的模板，先停用，等待后台绑定后再启用。
UPDATE points_product
SET status = 'disabled'
WHERE deleted = 0
  AND code IN ('points-matcha-buy-one', 'points-second-cup-half');

-- ---------- 6. 后台菜单与运营角色授权 ----------
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'marketing_points-category', p.id, '积分商城分类', '/marketing/points-category',
       'view.marketing_points-category', 'mdi:shape', 4, 'hidden', 1, 'ENABLE_STORED_VALUE'
FROM sys_menu p
WHERE p.code = 'marketing' AND p.deleted = 0
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    name = VALUES(name),
    path = VALUES(path),
    component = VALUES(component),
    icon = VALUES(icon),
    order_num = VALUES(order_num),
    type = VALUES(type),
    status = 1,
    feature_flag = VALUES(feature_flag),
    deleted = 0;

-- 新分类菜单插入 4，原积分商城及其后的菜单顺延到 5-10；
-- 优惠券、储值、礼品卡保持原 1-3，隐藏的签到规则保持原 order_num=0。
UPDATE sys_menu
SET order_num = CASE code
    WHEN 'marketing_points' THEN 5
    WHEN 'marketing_member' THEN 6
    WHEN 'marketing_comment' THEN 7
    WHEN 'marketing_gift-order' THEN 8
    WHEN 'marketing_exchange' THEN 9
    WHEN 'marketing_referral' THEN 10
    ELSE order_num
END
WHERE code IN (
    'marketing_points', 'marketing_member', 'marketing_comment',
    'marketing_gift-order', 'marketing_exchange', 'marketing_referral'
)
AND deleted = 0;

-- 不依赖 V39 的已执行回填语句，显式补运营角色关联。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.code = 'marketing_points-category' AND m.deleted = 0
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    update_time = CURRENT_TIMESTAMP;
