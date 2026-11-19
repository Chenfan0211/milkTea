-- V108：线上商城首页独立轮播。
CREATE TABLE IF NOT EXISTS mall_banner (
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
    KEY idx_mall_banner_status_sort (status, deleted, sort, id),
    CONSTRAINT chk_mall_banner_target_type
        CHECK (target_type IN ('none', 'mallProduct')),
    CONSTRAINT chk_mall_banner_target
        CHECK (
            (target_type = 'none' AND target_id = '')
            OR (target_type = 'mallProduct' AND target_id REGEXP '^[1-9][0-9]*$')
        )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='线上商城轮播图';

-- 商城管理 → 商城轮播。不写入演示轮播，未配置时小程序自动隐藏区域。
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'mall_banner', p.id, '商城轮播', '/mall/banner', 'view.mall_banner',
       'mdi:image-multiple-outline', 0, 'menu', 1, NULL
FROM sys_menu p WHERE p.code = 'mall' AND p.deleted = 0
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), path=VALUES(path),
    component=VALUES(component), icon=VALUES(icon), order_num=VALUES(order_num),
    type='menu', status=1, deleted=0;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0 AND m.code = 'mall_banner'
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, update_time = CURRENT_TIMESTAMP;
