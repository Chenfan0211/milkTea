-- =============================================================
-- V25：清理 R_ADMIN 死角色
--
-- 背景：R_ADMIN（运营管理员）是早期模板遗留的角色码。
--   前端路由（src/constants/admin.ts 的 ADMIN_ROLE）实际使用
--   R_SUPER / R_OPERATION / R_FINANCE / R_AUDIT 四个码，
--   从未引用 R_ADMIN；库中该角色亦无任何用户绑定（0 条）。
--   为避免角色列表出现"看似可选但无实际权限"的误导项，予以清理。
--
-- 安全性：删除前做绑定数校验，若存在绑定则跳过（宁可保留也不误删）。
-- 幂等：可重复执行。
--
-- 注意：不改动 V3__seed_base.sql —— Flyway 对已执行迁移做 checksum 校验，
--      修改历史迁移会导致启动时报 checksum mismatch。
-- =============================================================

-- 仅在「无任何用户绑定」时才删除，避免误删正在使用的角色
DELETE FROM sys_role
WHERE code = 'R_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_user_role ur WHERE ur.role_id = sys_role.id AND ur.deleted = 0
  );
