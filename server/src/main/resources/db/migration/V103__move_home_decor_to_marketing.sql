-- 首页装修与我的页轮播同属营销中心；保留原路由地址和权限。
UPDATE sys_menu m
JOIN sys_menu p ON p.code = 'marketing' AND p.deleted = 0
SET m.parent_id = p.id,
    m.order_num = 12
WHERE m.code = 'system_home-decor' AND m.deleted = 0;
