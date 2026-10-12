-- =============================================================
-- V74：回填门店活动城市绑定（存量数据修复）
--
-- 背景：V71 迁移时曾一次性把「当时已有门店的城市」回填为
--   store_profile.activity_city_id。但后续在后台「活动城市管理」页
--   新增活动城市时，走通用 CRUD（CrudService.create），只插入
--   activity_city 表，不会回填门店绑定，导致「导入活动城市后，
--   门店城市未绑定上」。
--
-- 本迁移把「已有 city_id 但 activity_city_id 为空」的门店回填到
-- 对应的活动城市，作为存量数据的一次性兜底修复；新增活动城市的
-- 自动回填已由 CrudService.create 的联动逻辑承担。
--
-- 幂等：只更新 activity_city_id IS NULL 的行，重复执行无副作用。
-- =============================================================

UPDATE store_profile sp
JOIN activity_city ac ON ac.region_id = sp.city_id AND ac.deleted = 0
SET sp.activity_city_id = ac.id
WHERE sp.deleted = 0
  AND sp.activity_city_id IS NULL
  AND sp.city_id IS NOT NULL;