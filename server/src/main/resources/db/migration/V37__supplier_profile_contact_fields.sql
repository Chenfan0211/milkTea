-- =====================================================================
-- V37：供应商档案补充基础联系方式字段
--
-- 背景（2026-09-25 需求）：
--   运营后台「供应商管理」页只能维护「编码 / 名称 / 状态」，
--   supplier_profile 表仅有 product_count + status，没有任何联系信息，
--   运营无法记录对接人、电话、地址等，沟通与对账都没有依据。
--
-- 本次新增（「联系方式组」，与 store_profile 的档案风格保持一致）：
--   contact_name   联系人
--   phone          联系电话  —— 业务要求为必填（后端接口校验）
--   address        地址
--   email          邮箱
--   remark         备注
--
-- 为什么选填的是这 5 个字段：
--   供应商在业务上承担「供货与结算」职责（product.supplier_subject_id 决定
--   分账时供应商分成），日常运营最需要的是「能联系上对方」，
--   故先补联系方式组，不涉及银行账号等敏感信息。
--
-- 【幂等设计】逐个字段用 information_schema 判断后再 ADD COLUMN；
--   重复执行安全（列已存在则跳过）。刻意不手工插入 Flyway 历史记录 ——
--   迁移必须由 Flyway 自行执行并记录（V31 曾因手工伪造 checksum 导致服务无法启动）。
-- =====================================================================

-- ---------- 1. contact_name 联系人 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'supplier_profile'
             AND column_name = 'contact_name');
SET @s := IF(@c = 0,
  'ALTER TABLE supplier_profile ADD COLUMN contact_name VARCHAR(64) NULL COMMENT ''联系人'' AFTER product_count',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 2. phone 联系电话 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'supplier_profile'
             AND column_name = 'phone');
SET @s := IF(@c = 0,
  'ALTER TABLE supplier_profile ADD COLUMN phone VARCHAR(32) NULL COMMENT ''联系电话（必填，接口校验）'' AFTER contact_name',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 3. address 地址 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'supplier_profile'
             AND column_name = 'address');
SET @s := IF(@c = 0,
  'ALTER TABLE supplier_profile ADD COLUMN address VARCHAR(255) NULL COMMENT ''地址'' AFTER phone',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 4. email 邮箱 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'supplier_profile'
             AND column_name = 'email');
SET @s := IF(@c = 0,
  'ALTER TABLE supplier_profile ADD COLUMN email VARCHAR(128) NULL COMMENT ''邮箱'' AFTER address',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 5. remark 备注 ----------
SET @c := (SELECT COUNT(*) FROM information_schema.columns
           WHERE table_schema = DATABASE() AND table_name = 'supplier_profile'
           AND column_name = 'remark');
SET @s := IF(@c = 0,
  'ALTER TABLE supplier_profile ADD COLUMN remark VARCHAR(255) NULL COMMENT ''备注'' AFTER email',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ---------- 6. 存量数据回填联系电话 ----------
-- phone 在接口层为必填，若不回填，存量 3 条供应商在「编辑」时会因
-- 必填校验无法保存（除非先补电话）。这里按主体 id 生成占位号码，
-- 并显式标注为「待补充」，便于运营识别后替换为真实号码。
UPDATE supplier_profile
SET phone = CONCAT('000-0000-', LPAD(subject_id, 4, '0')),
    remark = COALESCE(remark, '存量数据：联系电话待补充')
WHERE deleted = 0
  AND (phone IS NULL OR TRIM(phone) = '');