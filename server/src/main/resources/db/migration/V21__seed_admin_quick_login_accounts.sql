-- =============================================================
-- 运营后台快捷登录账号 seed
--
-- 背景：登录页「其他账号登录」提供 4 个快捷按钮（超级管理员 / 运营 / 财务 / 审计），
--      但 sys_user 中仅有 admin 一个账号，按钮点击必然登录失败。
--      本迁移补齐这 4 个账号及其角色，使按钮可直接登录进后台查看数据。
--
-- 角色对齐（以前端 src/constants/admin.ts 的 ADMIN_ROLE 为准）：
--   R_SUPER     超级管理员  -> 全部菜单
--   R_OPERATION 运营        -> 主体/用户/授权/商品/交易/营销
--   R_FINANCE   财务        -> 财务中心
--   R_AUDIT     审计        -> 系统审计
-- 数据库中已存在的 R_SUPER、R_ADMIN 保持不变（R_ADMIN 为历史角色，不再新增引用）。
--
-- 【重要】用户名 collation 为 utf8mb4_unicode_ci（不区分大小写），
--   即 'Admin' 与 'admin' 在库中视为同一账号，无法共存。
--   因此「运营」按钮必须使用独立用户名 operator，不能复用 'Admin'。
--   前端 src/views/_builtin/login/modules/pwd-login.vue 的该按钮已同步改为 operator。
--
-- 安全说明：以下账号为演示/联调用途，口令均为弱口令，正式上线前必须修改或停用。
-- =============================================================

-- ---------- 1. 补齐角色（R_SUPER / R_ADMIN 已存在，仅补缺失的 3 个）----------
-- 用 code 唯一键做幂等：重复执行不会报错，也不会覆盖已有角色的名称与状态。
INSERT INTO sys_role (code, name, data_scope, status) VALUES
('R_OPERATION', '运营',  'all', 1),
('R_FINANCE',   '财务',  'all', 1),
('R_AUDIT',     '审计',  'all', 1)
ON DUPLICATE KEY UPDATE code = VALUES(code);

-- ---------- 2. 新增 4 个快捷登录账号 ----------
-- 密码为 BCrypt（强度 $2a$10，与 V3 种子一致），明文均为：123456
-- 幂等：以 username 唯一键去重，已存在则只对齐昵称与状态，不覆盖口令。
INSERT INTO sys_user (username, password, nick_name, status) VALUES
('super',    '$2a$10$bB.2yNHwtOq3v5oGMLnyi./Ey6fS.DfyQKjq0l0LVDBDCEZdBrDzy', '超级管理员', 1),
('operator', '$2a$10$bB.2yNHwtOq3v5oGMLnyi./Ey6fS.DfyQKjq0l0LVDBDCEZdBrDzy', '运营',       1),
('Finance',  '$2a$10$bB.2yNHwtOq3v5oGMLnyi./Ey6fS.DfyQKjq0l0LVDBDCEZdBrDzy', '财务',       1),
('Audit',    '$2a$10$bB.2yNHwtOq3v5oGMLnyi./Ey6fS.DfyQKjq0l0LVDBDCEZdBrDzy', '审计',       1)
ON DUPLICATE KEY UPDATE nick_name = VALUES(nick_name), status = VALUES(status);

-- ---------- 3. 账号与角色绑定 ----------
-- 绑定前先按 (user_id, role_id) 判重，避免重复执行时违反唯一键。
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'R_SUPER'
WHERE u.username = 'super'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'R_OPERATION'
WHERE u.username = 'operator'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'R_OPERATION'
WHERE u.username = 'admin'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'R_FINANCE'
WHERE u.username = 'Finance'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = 'R_AUDIT'
WHERE u.username = 'Audit'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id
  );
