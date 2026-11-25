-- =============================================================
-- V116：入驻与签约菜单登记
--
-- 背景：新增「入驻与签约」页面（/subject/onboard）后必须同步登记 sys_menu，
-- 否则动态菜单模式下页面存在但侧边栏看不到（AdminRbacGuardTest 会拦住）。
--
-- 为什么不改 V39：V39 已在生产执行，Flyway 校验 checksum，
-- 已应用的迁移不可修改；新菜单一律用新迁移追加（与 V52/V76 的做法一致）。
--
-- 角色：运营负责跟进入驻进度，财务需查看结算卡状态以便审批提现。
-- =============================================================

-- 幂等：先清理可能存在的同 code 记录（含软删的），避免唯一键冲突
DELETE FROM sys_role_menu
WHERE menu_id IN (SELECT id FROM sys_menu WHERE code = 'subject_onboard');
DELETE FROM sys_menu WHERE code = 'subject_onboard';

-- 菜单节点：parent 指向已存在的 subject 目录
INSERT INTO sys_menu (code, parent_id, name, path, component, icon, order_num, type, status, feature_flag)
SELECT 'subject_onboard', p.id, '入驻与签约', '/subject/onboard',
       'view.subject_onboard', 'mdi:card-account-details', 9, 'menu', 1, NULL
FROM sys_menu p
WHERE p.code = 'subject' AND p.deleted = 0
LIMIT 1;

-- 角色授权：超管 / 运营 / 财务
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.code = 'subject_onboard' AND m.deleted = 0
WHERE r.code IN ('R_SUPER', 'R_OPERATION', 'R_FINANCE');
