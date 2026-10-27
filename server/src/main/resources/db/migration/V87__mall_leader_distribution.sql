-- =====================================================================
-- V87: 商城团长分销
--
-- 变更点：
--   1. mall_sku.distribution_commission：商城 SKU 分销佣金（分/件，固定金额）。
--      非团长用户购买时实付 = 售价 + 分销佣金；佣金在订单完成后分给该用户的
--      归属团长；无归属团长时并入平台尾差（不单独立账）。
--   2. user_leader：普通用户 → 团长用户的归属关系（一个用户最多一个团长）。
--      团长仅在后台配置（用户列表绑定 LEADER 经营角色），小程序不提供自助申请。
--   3. split_snapshot.leader_amount：团长分佣额（分），无团长时为 0，便于对账。
--   4. order_item.distribution_commission：下单时 SKU 分销佣金单价快照（分/件）。
--      同时 orders.split_rule_snapshot JSON 内追加 leaderSubjectId / leaderUserId，
--      供订单完成发布分账事件时定位团长主体。
-- =====================================================================

-- ---------- 1. mall_sku 分销佣金 ----------
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mall_sku' AND COLUMN_NAME = 'distribution_commission') = 0,
    'ALTER TABLE mall_sku ADD COLUMN distribution_commission BIGINT NOT NULL DEFAULT 0 COMMENT ''分销佣金(分/件)'' AFTER platform_commission',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 用户-团长归属 ----------
CREATE TABLE IF NOT EXISTS user_leader (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id        BIGINT UNSIGNED NOT NULL COMMENT '普通用户ID（唯一，一个用户最多一个团长）',
    leader_user_id BIGINT UNSIGNED NOT NULL COMMENT '团长用户ID',
    leader_subject_id BIGINT UNSIGNED NULL COMMENT '团长主体ID（user_role_grant/subject 解析，冗余便于分账）',
    create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_leader_user (user_id),
    KEY idx_user_leader_leader (leader_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户归属团长关系（仅后台配置）';

-- ---------- 3. 分账快照团长分佣额 ----------
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'split_snapshot' AND COLUMN_NAME = 'leader_amount') = 0,
    'ALTER TABLE split_snapshot ADD COLUMN leader_amount BIGINT NOT NULL DEFAULT 0 COMMENT ''团长分销佣金(分)，无团长为0'' AFTER supplier_amount',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. 订单明细分销佣金快照 ----------
SET @sql := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_item' AND COLUMN_NAME = 'distribution_commission') = 0,
    'ALTER TABLE order_item ADD COLUMN distribution_commission BIGINT NULL COMMENT ''分销佣金单价快照(分/件)'' AFTER platform_commission',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5. 团长经营角色注册（统一大写枚举口径） ----------
-- 不新增 sys_role（那是后台账号角色）；LEADER 走 user_role_grant / biz_subject subject_type，
-- 与 STORE / INVESTOR / CHANNEL 一致。此处仅预留校验视角，无 DDL。
