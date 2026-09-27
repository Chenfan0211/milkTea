-- =====================================================================
-- V58：积分获取规则结构化（数字可存储、可计算）
--
-- 背景：
--   原 points_earning_rule.reward 是 VARCHAR，奖励以文本存储
--   （如 '+1币'、'+3币/人'、'双倍时光币'），数字被埋在字符串里，
--   无法用于「按规则发放时光币」的计算。
--
-- 目标：
--   1) 把奖励拆成结构化数字字段，供后续发放逻辑直接使用；
--   2) 「行为」收敛为数据字典 points_action，后台页面只读、不可增删；
--   3) 每日上限独立成 daily_limit 字段（如分享每日上限 2 次）。
--
-- 奖励模型（通用，覆盖全部 6 条行为）：
--   reward_type   奖励类型：
--                   per-yuan   按消费金额比例（需要 basis_amount + reward_value）
--                   fixed      固定值（如签到固定 +1 币）
--                   fixed-per  按单位固定值（如 +3 币/人、+3 币/次）
--                   multiplier 倍数（如生日双倍、时光日 3 倍）
--   reward_value  币数 或 倍数
--   basis_amount  仅 per-yuan 使用：每 X（分）。如 100 = 每 1 元
--   basis_unit    计量单位：yuan / person / time / day
--   daily_limit   每日上限（次/天）；NULL 表示不限
--
-- 幂等原则：与 V53 一致，使用 WHERE NOT EXISTS + UPDATE，
--           列存在性用 information_schema 判断后动态执行，重复执行安全。
-- =====================================================================

-- ---------- 1. 新增结构化字段（幂等） ----------
-- 幂等：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
--       故用 information_schema 判断后动态执行（写法与 V23 一致）。
SET @tbl := 'points_earning_rule';

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'reward_type');
SET @sql := IF(@exist = 0,
  'ALTER TABLE points_earning_rule ADD COLUMN reward_type VARCHAR(32) NULL COMMENT ''奖励类型：per-yuan/fixed/fixed-per/multiplier'' AFTER reward',
  'SELECT ''reward_type 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'reward_value');
SET @sql := IF(@exist = 0,
  'ALTER TABLE points_earning_rule ADD COLUMN reward_value BIGINT NULL COMMENT ''币数或倍数'' AFTER reward_type',
  'SELECT ''reward_value 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'basis_amount');
SET @sql := IF(@exist = 0,
  'ALTER TABLE points_earning_rule ADD COLUMN basis_amount BIGINT NULL COMMENT ''仅 per-yuan：每 X（金额单位分）'' AFTER reward_value',
  'SELECT ''basis_amount 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'basis_unit');
SET @sql := IF(@exist = 0,
  'ALTER TABLE points_earning_rule ADD COLUMN basis_unit VARCHAR(16) NULL COMMENT ''计量单位：yuan/person/time/day'' AFTER basis_amount',
  'SELECT ''basis_unit 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'daily_limit');
SET @sql := IF(@exist = 0,
  'ALTER TABLE points_earning_rule ADD COLUMN daily_limit INT NULL COMMENT ''每日上限（次/天）；NULL 不限'' AFTER basis_unit',
  'SELECT ''daily_limit 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 行为字典（后台页面只读来源） ----------
INSERT INTO sys_dict_type (dict_type, dict_name, status)
SELECT 'points_action', '积分获取行为', 1
WHERE NOT EXISTS (
    SELECT 1 FROM sys_dict_type WHERE dict_type = 'points_action'
);

UPDATE sys_dict_type
SET dict_name = '积分获取行为', status = 1, deleted = 0
WHERE dict_type = 'points_action';

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'points_action', 'consume', '每消费1元', 10, 1
FROM sys_dict_type t
WHERE t.dict_type = 'points_action'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'points_action' AND i.item_code = 'consume'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'points_action', 'signin', '每日签到', 20, 1
FROM sys_dict_type t
WHERE t.dict_type = 'points_action'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'points_action' AND i.item_code = 'signin'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'points_action', 'invite', '邀请好友注册', 30, 1
FROM sys_dict_type t
WHERE t.dict_type = 'points_action'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'points_action' AND i.item_code = 'invite'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'points_action', 'share', '分享订单到朋友圈/小红书', 40, 1
FROM sys_dict_type t
WHERE t.dict_type = 'points_action'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'points_action' AND i.item_code = 'share'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'points_action', 'birthday', '生日当天消费', 50, 1
FROM sys_dict_type t
WHERE t.dict_type = 'points_action'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'points_action' AND i.item_code = 'birthday'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'points_action', 'member-day', '每周四"时光日"', 60, 1
FROM sys_dict_type t
WHERE t.dict_type = 'points_action'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'points_action' AND i.item_code = 'member-day'
  );

-- 统一修正 item_name 与排序（幂等）
UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.dict_type = 'points_action'
SET i.dict_type_id = t.id,
    i.item_name = CASE i.item_code
        WHEN 'consume'    THEN '每消费1元'
        WHEN 'signin'     THEN '每日签到'
        WHEN 'invite'     THEN '邀请好友注册'
        WHEN 'share'      THEN '分享订单到朋友圈/小红书'
        WHEN 'birthday'   THEN '生日当天消费'
        WHEN 'member-day' THEN '每周四"时光日"'
        ELSE i.item_name
    END,
    i.sort = CASE i.item_code
        WHEN 'consume'    THEN 10
        WHEN 'signin'     THEN 20
        WHEN 'invite'     THEN 30
        WHEN 'share'      THEN 40
        WHEN 'birthday'   THEN 50
        WHEN 'member-day' THEN 60
        ELSE i.sort
    END,
    i.enabled = 1,
    i.deleted = 0
WHERE i.dict_type = 'points_action'
  AND i.item_code IN ('consume','signin','invite','share','birthday','member-day');

-- ---------- 3. 历史数据回填（文本 -> 结构化数字） ----------
-- 注意：金额基准 basis_amount 单位为「分」，每消费 1 元 = 100。
UPDATE points_earning_rule SET
    reward_type   = 'per-yuan',
    reward_value  = 1,
    basis_amount  = 100,
    basis_unit    = 'yuan',
    daily_limit   = NULL,
    action        = '每消费1元'
WHERE code = 'consume';

UPDATE points_earning_rule SET
    reward_type   = 'fixed',
    reward_value  = 1,
    basis_amount  = NULL,
    basis_unit    = 'day',
    daily_limit   = 1,
    action        = '每日签到'
WHERE code = 'signin';

UPDATE points_earning_rule SET
    reward_type   = 'fixed-per',
    reward_value  = 3,
    basis_amount  = NULL,
    basis_unit    = 'person',
    daily_limit   = NULL,
    action        = '邀请好友注册'
WHERE code = 'invite';

UPDATE points_earning_rule SET
    reward_type   = 'fixed-per',
    reward_value  = 3,
    basis_amount  = NULL,
    basis_unit    = 'time',
    daily_limit   = 2,
    action        = '分享订单到朋友圈/小红书'
WHERE code = 'share';

UPDATE points_earning_rule SET
    reward_type   = 'multiplier',
    reward_value  = 2,
    basis_amount  = NULL,
    basis_unit    = 'time',
    daily_limit   = NULL,
    action        = '生日当天消费'
WHERE code = 'birthday';

UPDATE points_earning_rule SET
    reward_type   = 'multiplier',
    reward_value  = 3,
    basis_amount  = NULL,
    basis_unit    = 'time',
    daily_limit   = NULL,
    action        = '每周四"时光日"'
WHERE code = 'member-day';
