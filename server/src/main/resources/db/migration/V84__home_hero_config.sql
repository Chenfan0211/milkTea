-- V84：首页主视觉后台装修配置
-- 配置由后台“系统审计 → 首页装修”维护；小程序读取 app_config.home_hero。
-- imageUrl 为空时，小程序回退静态 /assets/images/3x/home-hero.jpg。
INSERT INTO app_config (config_key, config_name, value, sort, status, remark) VALUES
('home_hero', '首页主视觉背景图', '{"imageUrl":"","altText":"金秋入茶限定上新","updatedAt":""}', 9, 'enabled', '首页主视觉背景图；请通过“首页装修”页面维护')
ON DUPLICATE KEY UPDATE
  config_name = VALUES(config_name),
  value = VALUES(value),
  sort = VALUES(sort),
  status = VALUES(status),
  remark = VALUES(remark);

-- 首页装修菜单：挂在“系统审计”下，仅开放给超管与运营。
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'system_home-decor', p.id, '首页装修', '/system/home-decor',
       'view.system_home-decor', 'mdi:monitor-dashboard', 6, 'menu', 1, NULL
FROM sys_menu p
WHERE p.code = 'system' AND p.deleted = 0
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

-- 显式补充运营角色；R_SUPER 由前端静态超管角色默认可见全部菜单。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.code = 'system_home-decor' AND m.deleted = 0
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    update_time = CURRENT_TIMESTAMP;