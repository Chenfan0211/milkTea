-- =============================================================
-- V28：存量商品储值价回填
--
-- 背景：储值价此前允许为空（默认按原价 80% 计算），现改为必填。
--       本迁移把存量 stored_value_price = 0 的商品按原价 80% 一次性回填，
--       后续新增/编辑必须由运营人工填写，不再自动计算。
--
-- 幂等：仅回填 stored_value_price = 0 且 original_price > 0 的行。
-- =============================================================

UPDATE product
SET stored_value_price = ROUND(original_price * 0.8)
WHERE stored_value_price = 0
  AND original_price > 0
  AND deleted = 0;
