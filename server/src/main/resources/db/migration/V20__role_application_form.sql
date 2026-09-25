-- =============================================================
-- V20：角色申请表单明细落库
--
-- 背景：小程序端提交经营角色申请时填写了姓名 / 手机号 / 门店信息等，
-- 此前接口只落库 role_type + status，表单明细被丢弃，运营无法据此审核。
--
-- 设计：
--   · applicant_name / applicant_phone 提为独立列 —— 高频查询字段（按手机号检索申请人）；
--   · extra_form 存各角色差异化字段的 JSON（门店名称/地址/类型、投资点位/预算等），
--     避免为三种角色分别建表或频繁加列；
--   · 与 V16 保持同样的迁移风格：先判断列是否存在再加，保证可重复执行安全。
--
-- 兼容：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 扩展），
--       故用 information_schema 判断后再执行，避免迁移失败。
-- =============================================================

-- 申请人姓名
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'role_application'
               AND COLUMN_NAME = 'applicant_name');
SET @sql := IF(@col = 0,
  'ALTER TABLE role_application ADD COLUMN applicant_name VARCHAR(64) NULL COMMENT ''申请人姓名'' AFTER role_type',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 申请人手机号
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'role_application'
               AND COLUMN_NAME = 'applicant_phone');
SET @sql := IF(@col = 0,
  'ALTER TABLE role_application ADD COLUMN applicant_phone VARCHAR(32) NULL COMMENT ''申请人手机号'' AFTER applicant_name',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 表单明细（各角色差异化字段的 JSON）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'role_application'
               AND COLUMN_NAME = 'extra_form');
SET @sql := IF(@col = 0,
  'ALTER TABLE role_application ADD COLUMN extra_form JSON NULL COMMENT ''申请表单明细(JSON)'' AFTER applicant_phone',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
