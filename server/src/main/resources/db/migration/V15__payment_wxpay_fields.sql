-- =============================================================
-- 第 14 期：微信支付接入所需字段
--
-- 背景：payment 表原有字段足够记录「支付单 + 状态 + 第三方交易号」，
-- 但微信支付对账与退款还需两个字段：
--   prepay_id    ：微信预支付会话标识。用户唤起收银台但未付款时，
--                  只有它能定位到微信侧的预下单记录。
--   payer_openid ：支付者 openid。微信退款必须原路退回，
--                  需要知道付款人；缺失会导致退款时只能人工查单。
--
-- 说明：两列均可为空 —— 历史数据与 mock 通道支付单不会有值，
-- 加 NOT NULL 会导致迁移失败。
-- =============================================================

ALTER TABLE payment
    ADD COLUMN prepay_id    VARCHAR(64) NULL COMMENT '微信预支付会话标识' AFTER transaction_id,
    ADD COLUMN payer_openid VARCHAR(64) NULL COMMENT '支付者 openid' AFTER prepay_id;
