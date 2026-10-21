-- =====================================================================
-- V76：茶机设备菜单（主体管理 → 设备管理 / 冲泡记录）
--
-- 背景：
--   对接慧养智萃茶机队列后，运营需要在后台维护设备台账（绑定门店）
--   并查看设备上报的胶囊冲泡记录，对应前端两个新页面：
--     /subject/device       view.subject_device       设备管理
--     /subject/device-brew  view.subject_device-brew  冲泡记录
--
-- 为什么新建迁移、而不是去改 V39 的菜单种子表：
--   V39 已在生产执行，Flyway 会校验历史迁移的 checksum，
--   修改已应用的迁移会导致服务启动失败（与 09-22 生产事故同类风险）。
--   后续新增菜单一律走「新迁移 + ON DUPLICATE KEY UPDATE」这条路。
--
-- 【幂等】菜单以 code 唯一键 upsert；角色关联用 sys_role_menu 唯一键 upsert。
-- =====================================================================

-- ---------- 1. 设备管理菜单 ----------
-- 注意两条插入分开写：父节点 sys_menu('subject') 此时已存在于库中，
-- 子查询可正常解析出 parent_id（V39 踩过「同批插入 JOIN 看不到新父节点」的坑）。
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'subject_device', p.id, '设备管理', '/subject/device',
       'view.subject_device', 'mdi:devices', 6, 'menu', 1, NULL
FROM sys_menu p
WHERE p.code = 'subject' AND p.deleted = 0
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

-- ---------- 2. 冲泡记录菜单 ----------
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'subject_device-brew', p.id, '冲泡记录', '/subject/device-brew',
       'view.subject_device-brew', 'mdi:tea', 7, 'menu', 1, NULL
FROM sys_menu p
WHERE p.code = 'subject' AND p.deleted = 0
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

-- ---------- 3. 给运营角色授权 ----------
-- 与前端路由 meta.roles = ['R_SUPER', 'R_OPERATION'] 对齐：
-- 超管按前端静态超管角色默认可见全部菜单，这里只需显式补 R_OPERATION，
-- 否则动态菜单模式下普通运营看不到入口（页面存在、侧边栏没有）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.code IN ('subject_device', 'subject_device-brew') AND m.deleted = 0
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE
    deleted = 0,
    update_time = CURRENT_TIMESTAMP;
