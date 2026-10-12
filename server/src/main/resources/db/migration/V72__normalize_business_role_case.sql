-- =============================================================
-- V72：归一 app_user.business_role 与 user_role_grant.role_code 大小写
--
-- 背景（2026-09 经营角色链路排查）：
--   审核路径 SubjectBindingController#reviewApplication 历史上用
--   String.valueOf(row.get("role_type")).toLowerCase() 直接写入
--   app_user.business_role 与 user_role_grant.role_code，
--   与 bindUserRole 的 normalizeRoleCode（toUpperCase）口径不一致。
--
--   后果链：
--     1) user_role_grant.role_code 存成小写 'store'，但 AppRoleController#mine
--        与 InternalQueryController#storeOperatorCheck 都按大写 'STORE' 读
--        → 角色读不到 / 越权 403；
--     2) app_user.business_role 存成小写，V36 的「补全角色」只处理
--        business_role IS NULL 的记录，放过了小写脏值。
--
-- 权威口径（与 bindUserRole / V36 一致，全大写）：
--   STORE / INVESTOR / CHANNEL
--
-- 幂等：纯 UPDATE ... WHERE UPPER(...) IN (...)，无 DDL；
--       重复执行结果一致（第二次已无待归并行，影响 0 行）。
-- =============================================================

-- 1) 归并 app_user.business_role 的小写/中文脏值为权威大写
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

-- 2) 归并 user_role_grant.role_code 为权威大写（保持与授权判定一致）
UPDATE user_role_grant
SET role_code = CASE UPPER(TRIM(role_code))
    WHEN 'STORE'    THEN 'STORE'
    WHEN 'INVESTOR' THEN 'INVESTOR'
    WHEN 'CHANNEL'  THEN 'CHANNEL'
    WHEN 'RESOURCE' THEN 'CHANNEL'
    ELSE role_code
END
WHERE deleted = 0
  AND role_code IS NOT NULL
  AND TRIM(role_code) <> '';

-- 3) 校验：应无残留的非法小写/未知角色值
SELECT DISTINCT business_role AS remaining_unknown_role
FROM app_user
WHERE deleted = 0
  AND business_role IS NOT NULL
  AND TRIM(business_role) <> ''
  AND business_role NOT IN ('STORE', 'INVESTOR', 'CHANNEL');
