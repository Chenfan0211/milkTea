-- =====================================================================
-- V91: 商城售后运营菜单
-- =====================================================================
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'mall_after-sale', p.id, '售后审核', '/mall/after-sale', 'view.mall_after-sale',
       'mdi:clipboard-check-outline', 4, 'menu', 1, NULL
FROM sys_menu p WHERE p.code = 'mall' AND p.deleted = 0
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id), name=VALUES(name), path=VALUES(path),
    component=VALUES(component), icon=VALUES(icon), order_num=VALUES(order_num),
    type='menu', status=1, deleted=0;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = 0 AND m.code = 'mall_after-sale'
WHERE r.code = 'R_OPERATION' AND r.deleted = 0
ON DUPLICATE KEY UPDATE deleted = 0, update_time = CURRENT_TIMESTAMP;

