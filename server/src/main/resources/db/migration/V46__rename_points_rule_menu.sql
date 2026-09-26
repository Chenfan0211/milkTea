-- V46：运营后台菜单展示名修正。
-- 菜单 code 是权限与前端路由锚点，保持不变；仅将旧种子中的占位名改为业务名称。
UPDATE sys_menu
SET name = '签到规则'
WHERE code = 'marketing_points-rule'
  AND deleted = 0;