-- 首页轮播改为逐条管理；旧 home_hero 配置只用于一次性迁移。
CREATE TABLE home_banner (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    image_url   VARCHAR(512) NOT NULL,
    alt_text    VARCHAR(80) NOT NULL DEFAULT '',
    target_type VARCHAR(32) NOT NULL DEFAULT 'none',
    target_id   VARCHAR(64) NOT NULL DEFAULT '',
    sort        INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_home_banner_status_sort (status, deleted, sort, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首页轮播图';

-- 已有多图优先；仅在没有 banners 数组时按旧单图格式迁移。
INSERT INTO home_banner (image_url, alt_text, target_type, target_id, sort, status)
SELECT b.image_url, COALESCE(NULLIF(b.alt_text, ''), '首页活动图'),
       COALESCE(NULLIF(b.target_type, ''), 'none'), COALESCE(b.target_id, ''), b.ordinal, 'enabled'
FROM app_config c
JOIN JSON_TABLE(c.value, '$.banners[*]' COLUMNS (
    ordinal FOR ORDINALITY,
    image_url VARCHAR(512) PATH '$.imageUrl',
    alt_text VARCHAR(80) PATH '$.altText',
    target_type VARCHAR(32) PATH '$.targetType',
    target_id VARCHAR(64) PATH '$.targetId'
)) b
WHERE c.config_key = 'home_hero' AND c.deleted = 0
  AND NULLIF(TRIM(b.image_url), '') IS NOT NULL;

INSERT INTO home_banner (image_url, alt_text, target_type, target_id, sort, status)
SELECT JSON_UNQUOTE(JSON_EXTRACT(c.value, '$.imageUrl')),
       COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(c.value, '$.altText')), ''), '首页活动图'),
       'none', '', 1, 'enabled'
FROM app_config c
WHERE c.config_key = 'home_hero' AND c.deleted = 0
  AND COALESCE(JSON_TYPE(JSON_EXTRACT(c.value, '$.banners')), '') <> 'ARRAY'
  AND NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(c.value, '$.imageUrl'))), '') IS NOT NULL;
