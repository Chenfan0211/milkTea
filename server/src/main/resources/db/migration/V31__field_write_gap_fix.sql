-- =============================================================
-- 全站字段写库排查：补齐确实缺失的列
--
-- 背景：2026-09-25 全站排查发现，多个页面的表单字段因「库中无对应列」
--       而被通用 CRUD 白名单静默丢弃，表现为「填了数据但页面看不到反应」。
--
-- 原则：仅补「库里确实没有对应语义」的列；
--       若已有同义列（如 name vs title），一律改前端适配已有列，不重复建列。
--
-- 【幂等设计】本脚本必须可重复执行：
--   本次改动先在库上手工验证过，若直接用 ALTER TABLE 会因列已存在而报
--   Duplicate column，进而被 Flyway 记为 failed，导致服务无法启动
--   （V30 已踩过此坑）。故这里统一用 information_schema 判断后再执行。
-- =============================================================

-- ---------- 1. 平台主体：提现免审阈值 ----------
-- 单位「分」，0 表示无免审额度（任何提现都需审核）
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'biz_subject'
             AND column_name = 'withdraw_free_audit_threshold');
SET @s := IF(@c = 0,
  'ALTER TABLE biz_subject ADD COLUMN withdraw_free_audit_threshold BIGINT NOT NULL DEFAULT 0 COMMENT ''提现免审阈值（分），0=无免审''',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 2. 优惠券：渠道与支付限制 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'coupon'
             AND column_name = 'channel');
SET @s := IF(@c = 0,
  'ALTER TABLE coupon ADD COLUMN channel VARCHAR(64) NULL COMMENT ''发放渠道'' AFTER source',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'coupon'
             AND column_name = 'payment_restriction');
SET @s := IF(@c = 0,
  'ALTER TABLE coupon ADD COLUMN payment_restriction VARCHAR(64) NULL COMMENT ''支付方式限制'' AFTER channel',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 3. 城市：经纬度 ----------
-- 说明：省-市关系沿用 region.parent_id（level=1 为省，level=2 为市），
--       故不新增 province_code；但经纬度在 region 中确实缺失，需补。
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'region'
             AND column_name = 'latitude');
SET @s := IF(@c = 0,
  'ALTER TABLE region ADD COLUMN latitude DECIMAL(10,6) NULL COMMENT ''纬度'' AFTER sort',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'region'
             AND column_name = 'longitude');
SET @s := IF(@c = 0,
  'ALTER TABLE region ADD COLUMN longitude DECIMAL(10,6) NULL COMMENT ''经度'' AFTER latitude',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 4. 回填既有城市经纬度（与 V3/V4 种子口径一致，仅回填空值） ----------
UPDATE region SET latitude = 28.2282, longitude = 112.9388 WHERE code = '4301' AND latitude IS NULL;
UPDATE region SET latitude = 23.1291, longitude = 113.2644 WHERE code = '4401' AND latitude IS NULL;
UPDATE region SET latitude = 22.5431, longitude = 114.0579 WHERE code = '4403' AND latitude IS NULL;

-- ---------- 5. 白名单同步说明（见 CrudRegistry.java） ----------
-- 仅加列不够，还须在 server 侧 CrudRegistry 中放行这些字段，
-- 否则写入仍会被 CrudService.filterWritable 静默丢弃：
--   subjects -> withdraw_free_audit_threshold
--   coupons  -> channel, payment_restriction, validity_start, validity_end
--   cities   -> latitude, longitude
