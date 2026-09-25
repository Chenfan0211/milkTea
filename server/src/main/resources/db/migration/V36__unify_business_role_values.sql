-- =====================================================================
-- V36：统一 app_user 经营角色取值 + 修复绑定双向一致性
--
-- 背景（2026-09-25 用户反馈「解绑了还显示数据 / 经营角色显示英文 STORE」）：
--   1) app_user.business_role 存在多种命名混存：大写 STORE / 小写 store / 中文「门店」，
--      导致运营后台「经营角色」列因映射不中而原样显示英文。
--   2) 历史数据中存在「app_user 已绑定主体，但 biz_subject.bound_user_id 为空」的
--      单向不一致（bindUserRole 早期只写 app_user 一侧），
--      表现为「用户列表能看到绑定、主体列表看不到」。
--   3) 存在「bound_subject_id 有值但 business_role 为空」的不一致记录。
--
-- 权威口径（与后端 SubjectBindingController#bindUserRole 写入值一致）：
--   STORE / INVESTOR / CHANNEL   —— 后端一律 .toUpperCase() 后写入
--   资源方 = CHANNEL（前端 RoleType 叫 resource，仅前端别名，库内统一 CHANNEL）
--
-- 【幂等设计】纯 UPDATE ... WHERE，无 DDL；重复执行结果一致
--   （第二次执行时已无待归并行，UPDATE 影响 0 行）。
--   刻意不做「人工插入历史表记录」的动作 —— V31 已踩过 checksum 不匹配
--   导致服务无法启动的坑，迁移必须由 Flyway 自行执行并记录。
-- =====================================================================

-- ---------- 1. 归并 business_role 存量脏值为权威枚举 ----------
UPDATE app_user
SET business_role = CASE UPPER(TRIM(business_role))
    WHEN 'STORE'    THEN 'STORE'
    WHEN '门店'      THEN 'STORE'
    WHEN 'INVESTOR' THEN 'INVESTOR'
    WHEN '投资人'    THEN 'INVESTOR'
    WHEN 'CHANNEL'  THEN 'CHANNEL'
    WHEN 'RESOURCE' THEN 'CHANNEL'
    WHEN '资源方'    THEN 'CHANNEL'
    ELSE business_role
END
WHERE deleted = 0
  AND business_role IS NOT NULL
  AND TRIM(business_role) <> '';

-- ---------- 2. 反向补全 biz_subject.bound_user_id（修复单向不一致） ----------
-- 以 app_user 为准回填主体侧，保证「用户列表」与「主体列表」看到同一份绑定关系。
-- 只在主体尚未绑定用户（或绑的不是同一人）时回填，避免覆盖正确数据。
UPDATE biz_subject s
JOIN app_user u
  ON u.bound_subject_id = s.id
 AND u.deleted = 0
 AND u.business_role IS NOT NULL
SET s.bound_user_id = u.id
WHERE s.deleted = 0
  AND (s.bound_user_id IS NULL OR s.bound_user_id <> u.id);

-- ---------- 3. 补全「已绑定主体但角色为空」的记录 ----------
-- 角色取值直接取主体类型，与 bindUserRole 的口径保持一致。
UPDATE app_user u
JOIN biz_subject s
  ON s.id = u.bound_subject_id
 AND s.deleted = 0
SET u.business_role = CASE UPPER(s.subject_type)
    WHEN 'STORE'    THEN 'STORE'
    WHEN 'INVESTOR' THEN 'INVESTOR'
    WHEN 'CHANNEL'  THEN 'CHANNEL'
    ELSE u.business_role
END
WHERE u.deleted = 0
  AND u.bound_subject_id IS NOT NULL
  AND (u.business_role IS NULL OR TRIM(u.business_role) = '');

-- ---------- 4. 清理悬空绑定：bound_subject_id 指向已删除/不存在的主体 ----------
-- 这类记录会让前端「经营角色」显示角色、但主体名解析不出来，属于脏数据。
UPDATE app_user u
LEFT JOIN biz_subject s
  ON s.id = u.bound_subject_id
 AND s.deleted = 0
SET u.business_role = NULL,
    u.bound_subject_id = NULL
WHERE u.deleted = 0
  AND u.bound_subject_id IS NOT NULL
  AND s.id IS NULL;

-- ---------- 5. 校验：应无非法角色值残留 ----------
-- 若查询结果非空，说明存在本脚本未覆盖的新枚举值，需补充映射。
SELECT DISTINCT business_role AS remaining_unknown_role
FROM app_user
WHERE deleted = 0
  AND business_role IS NOT NULL
  AND TRIM(business_role) <> ''
  AND business_role NOT IN ('STORE', 'INVESTOR', 'CHANNEL');
