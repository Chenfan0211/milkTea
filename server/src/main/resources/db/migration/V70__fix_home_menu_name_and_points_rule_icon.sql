-- =====================================================================
-- V70：后台菜单展示修正（首页命名 + 签到规则图标）
--
-- 背景（2026-09-30 需求）：
--   1) 左侧菜单首项显示为 "home"（V39 种子里的历史占位名），需改为「首页」；
--   2) 「营销中心 → 签到规则」缺少图标，需补一个日历类图标。
--
-- 为什么不改 code：
--   sys_menu.code 与前端路由 name、sys_role_menu 权限关联一一对应，
--   是权限判定与路由锚点。改名只动展示字段 name / icon。
--
-- 幂等：两条 UPDATE 都带「值不同才更新」条件，可重复执行。
-- =====================================================================

-- 1) 首页菜单名：home -> 首页
UPDATE sys_menu
SET name = '首页'
WHERE code = 'home'
  AND deleted = 0
  AND name <> '首页';

-- 2) 签到规则菜单图标：NULL -> mdi:calendar-check
--    图标名会被 scripts/generate-iconify-offline.mjs 扫描并打进前端离线集合，
--    改完需执行 `node scripts/generate-iconify-offline.mjs` 重建 offline-icons.json。
UPDATE sys_menu
SET icon = 'mdi:calendar-check'
WHERE code = 'marketing_points-rule'
  AND deleted = 0
  AND (icon IS NULL OR icon = '');