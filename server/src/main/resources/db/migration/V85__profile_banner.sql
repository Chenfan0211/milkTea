-- V85：“我的”页轮播配置
-- 轮播统一点击进入点单页，因此表内不保存跳转字段。
CREATE TABLE profile_banner (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    image_url   VARCHAR(512) NOT NULL,
    alt_text    VARCHAR(80) NOT NULL DEFAULT '',
    sort        INT NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'enabled',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_profile_banner_status_sort (status, deleted, sort, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='我的页轮播图';

-- 首条数据保留当前线上横幅，后台可继续替换或停用。
INSERT INTO profile_banner (image_url, alt_text, sort, status)
SELECT '/assets/images/3x/profile-banner.jpg', '抹茶轻茉莉新品', 1, 'enabled'
WHERE NOT EXISTS (
    SELECT 1 FROM (SELECT id FROM profile_banner WHERE image_url = '/assets/images/3x/profile-banner.jpg' LIMIT 1) t
);

-- 营销中心菜单：我的页轮播。
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'marketing_profile-banner', p.id, '我的页轮播', '/marketing/profile-banner',
       'view.marketing_profile-banner', 'mdi:monitor-dashboard', 11, 'menu', 1, NULL
FROM sys_menu p
WHERE p.code = 'marketing' AND p.deleted = 0
ON DUPLICATE KEY UPDATE
    parent_id    = VALUES(parent_id),
    name         = VALUES(name),
    path         = VALUES(path),
    component    = VALUES(component),
    icon         = VALUES(icon),
    order_num    = VALUES(order_num),
    type         = 'menu',
    status       = 1,
    feature_flag = VALUES(feature_flag),
    deleted      = 0;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.code = 'marketing_profile-banner' AND m.deleted = 0
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    update_time = CURRENT_TIMESTAMP;