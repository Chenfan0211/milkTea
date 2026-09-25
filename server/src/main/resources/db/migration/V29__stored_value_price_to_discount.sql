-- =============================================================
-- V29：储值字段语义调整：到手价 -> 立减金额
--
-- 背景：stored_value_price 原语义为「储值付款到手价」（绝对金额），
--       现调整为「储值立减金额」（用储值余额支付时每件商品少多少分）。
--       计算顺序：先按用户等级算会员价，再减立减金额。
--
-- 本迁移把存量「到手价」换算为「立减金额」：
--   stored_value_price = original_price - stored_value_price
-- 仅处理 stored_value_price 在 (0, original_price) 区间的行，
-- 避免对已是立减金额或脏数据（<=0、>=原价）误换算。
--
-- 幂等：执行后 stored_value_price 变为立减金额，重复执行会再次换算，
--       故依赖 Flyway 只执行一次（version 唯一）。
-- =============================================================

UPDATE product
SET stored_value_price = original_price - stored_value_price
WHERE deleted = 0
  AND original_price > 0
  AND stored_value_price > 0
  AND stored_value_price < original_price;
