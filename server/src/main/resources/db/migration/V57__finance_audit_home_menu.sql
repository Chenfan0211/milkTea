-- =====================================================================
-- V57：为财务 / 审计角色补 home（首页）菜单权限
--
-- 背景（线上问题）：
--   用户反馈「财务账号登录后 404」。
--   根因：V39 回填角色菜单时，
--     · R_OPERATION 用「排除法」，天然包含 home；
--     · R_FINANCE 只回填 code = 'finance' 或 finance_*；
--     · R_AUDIT   只回填 system / system_audit / system_feature。
--   三者都没有 home —— 财务/审计账号登录后跳 /home，
--   因动态路由未注册 /home 而被前端 not-found 捕获，表现为 404。
--
-- 修复分两层（本迁移只做数据层，代码层兜底见 RouteController）：
--   · 代码层：/route/getUserRoutes 始终把 home 路由拼进返回结果（基础能力，人人可用）；
--   · 数据层（本迁移）：给 R_FINANCE / R_AUDIT 补 home 关联，
--     让「角色与权限」页的权限树能正确回显 home 已勾选，
--     避免运营在页面上看到「未勾选」而产生困惑。
--
-- 幂等：INSERT ... SELECT + NOT EXISTS，可重复执行。
-- =====================================================================

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.code = 'home' AND m.deleted = 0
WHERE r.code IN ('R_FINANCE', 'R_AUDIT')
  AND r.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
  );
