-- =====================================================================
-- V71：活动城市（运营开城白名单）与门店强绑定
--
-- 背景：region 是行政区划基础数据（全国 337 个市），不具备「是否开通运营」
--   语义；小程序端此前把全国城市都暴露给用户。本迁移在 region 之上加一层
--   活动城市白名单，只有活动城市才在小程序端显示。
--
-- 决策：
--   · region 保持只读基础数据，活动城市独立建表；
--   · 门店通过 activity_city_id 关联活动城市（在已有 city_id 基础上再加强约束）；
--   · store_profile.city / city_id 保留（city_id 是历史强关联，city 是冗余文本）；
--   · 初始化：把当前已有门店的城市全部落为活动城市，避免小程序选城变空。
-- =====================================================================

CREATE TABLE IF NOT EXISTS activity_city (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    region_id   BIGINT UNSIGNED NOT NULL COMMENT '关联 region.id（level=2 的市）',
    city_code   VARCHAR(32)  NOT NULL COMMENT '城市编码（冗余 region.code，便于查询）',
    city_name   VARCHAR(64)  NOT NULL COMMENT '城市名（冗余 region.name，便于展示）',
    province_id BIGINT UNSIGNED NULL COMMENT '所属省 region.id',
    status      VARCHAR(16)  NOT NULL DEFAULT 'enabled' COMMENT 'enabled启用 disabled停用',
    sort        INT          NOT NULL DEFAULT 0,
    open_time   DATETIME     NULL COMMENT '开城时间',
    remark      VARCHAR(255) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_city_region (region_id, deleted),
    KEY idx_activity_city_status (status, sort),
    KEY idx_activity_city_code (city_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='活动城市（运营白名单）';

-- ---------- 初始化：当前已有门店的城市自动成为活动城市 ----------
-- 门店已通过 store_profile.city_id 关联 region.id；据此反查 region 落活动城市。
INSERT INTO activity_city (region_id, city_code, city_name, province_id, status, sort)
SELECT r.id, r.code, r.name, r.parent_id, 'enabled', r.sort
FROM region r
WHERE r.level = 2
  AND r.deleted = 0
  AND r.id IN (
      SELECT DISTINCT sp.city_id FROM store_profile sp
      WHERE sp.deleted = 0 AND sp.city_id IS NOT NULL
  )
ON DUPLICATE KEY UPDATE city_name = VALUES(city_name);

-- ---------- 门店加活动城市关联列 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store_profile'
             AND COLUMN_NAME = 'activity_city_id');
SET @s := IF(@c = 0,
  'ALTER TABLE store_profile ADD COLUMN activity_city_id BIGINT UNSIGNED NULL COMMENT ''绑定的活动城市 id（强约束）'' AFTER city_id',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 按 city_id 反查回填门店的活动城市绑定
UPDATE store_profile sp
JOIN activity_city ac ON ac.region_id = sp.city_id AND ac.deleted = 0
SET sp.activity_city_id = ac.id
WHERE sp.deleted = 0 AND sp.activity_city_id IS NULL AND sp.city_id IS NOT NULL;

SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'store_profile' AND INDEX_NAME = 'idx_store_profile_activity_city');
SET @s := IF(@idx = 0,
  'ALTER TABLE store_profile ADD INDEX idx_store_profile_activity_city (activity_city_id)',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
