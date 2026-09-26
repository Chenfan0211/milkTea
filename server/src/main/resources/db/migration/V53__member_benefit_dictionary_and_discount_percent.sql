-- =====================================================================
-- V53：会员权益字典与折扣百分比规范
--
-- 目标：
--   1) 新增 member_benefit 数据字典，作为会员等级权益文案的唯一来源；
--   2) 将历史折扣 8折 / 0.8 / 80% 统一迁移为整数百分比字符串 80；
--   3) 无法识别的历史值保持原样，避免迁移误伤脏数据。
--
-- 幂等原则：sys_dict_item 没有 (dict_type, item_code) 唯一索引，
--           因此使用 INSERT ... SELECT ... WHERE NOT EXISTS + UPDATE，
--           不手工维护迁移历史。
-- =====================================================================

-- ---------- 1. 会员权益字典类型 ----------
INSERT INTO sys_dict_type (dict_type, dict_name, status)
SELECT 'member_benefit', '会员权益', 1
WHERE NOT EXISTS (
    SELECT 1
    FROM sys_dict_type
    WHERE dict_type = 'member_benefit'
);

UPDATE sys_dict_type
SET dict_name = '会员权益',
    status = 1,
    deleted = 0
WHERE dict_type = 'member_benefit';

-- ---------- 2. 会员权益字典项 ----------
INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'base_discount', '基础折扣', 10, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'base_discount'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'birthday_double_points', '生日月双倍时光币', 20, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'birthday_double_points'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'member_price', '专属会员价', 30, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'member_price'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'member_coupon', '专属优惠券', 40, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'member_coupon'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'new_product_trial', '新品优先体验', 50, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'new_product_trial'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'points_1_5x', '时光币1.5倍', 60, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'points_1_5x'
  );

INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled)
SELECT t.id, 'member_benefit', 'birthday_free_drink', '生日免费饮品', 70, 1
FROM sys_dict_type t
WHERE t.dict_type = 'member_benefit'
  AND NOT EXISTS (
      SELECT 1 FROM sys_dict_item i
      WHERE i.dict_type = 'member_benefit' AND i.item_code = 'birthday_free_drink'
  );

UPDATE sys_dict_item i
JOIN sys_dict_type t ON t.dict_type = 'member_benefit'
SET i.dict_type_id = t.id,
    i.item_name = CASE i.item_code
        WHEN 'base_discount' THEN '基础折扣'
        WHEN 'birthday_double_points' THEN '生日月双倍时光币'
        WHEN 'member_price' THEN '专属会员价'
        WHEN 'member_coupon' THEN '专属优惠券'
        WHEN 'new_product_trial' THEN '新品优先体验'
        WHEN 'points_1_5x' THEN '时光币1.5倍'
        WHEN 'birthday_free_drink' THEN '生日免费饮品'
        ELSE i.item_name
    END,
    i.sort = CASE i.item_code
        WHEN 'base_discount' THEN 10
        WHEN 'birthday_double_points' THEN 20
        WHEN 'member_price' THEN 30
        WHEN 'member_coupon' THEN 40
        WHEN 'new_product_trial' THEN 50
        WHEN 'points_1_5x' THEN 60
        WHEN 'birthday_free_drink' THEN 70
        ELSE i.sort
    END,
    i.enabled = 1,
    i.deleted = 0
WHERE i.dict_type = 'member_benefit'
  AND i.item_code IN (
      'base_discount', 'birthday_double_points', 'member_price',
      'member_coupon', 'new_product_trial', 'points_1_5x', 'birthday_free_drink'
  );

-- ---------- 3. 折扣迁移为整数百分比字符串 ----------
-- 1~9.9折 -> 10~99；0.x -> x%；1%~100% -> 1~100；未知格式保持原值。
UPDATE member_level
SET discount = CASE
    WHEN TRIM(discount) REGEXP '^([1-9]([.][0-9]+)?|10([.]0+)?)折$' THEN
        CAST(ROUND(CAST(REPLACE(TRIM(discount), '折', '') AS DECIMAL(10, 4)) * 10, 0) AS CHAR)
    WHEN TRIM(discount) REGEXP '^([1-9][0-9]?|100)([.]0+)?%$' THEN
        CAST(ROUND(CAST(REPLACE(TRIM(discount), '%', '') AS DECIMAL(10, 4)), 0) AS CHAR)
    WHEN TRIM(discount) REGEXP '^0[.][0-9]+$' THEN
        CAST(ROUND(CAST(TRIM(discount) AS DECIMAL(10, 4)) * 100, 0) AS CHAR)
    ELSE discount
END
WHERE TRIM(discount) REGEXP '^(([1-9]([.][0-9]+)?|10([.]0+)?)折|([1-9][0-9]?|100)([.]0+)?%|0[.][0-9]+)$';