-- V47：分享有礼首单奖励配置与默认奖励券。
--
-- referral_config 是奖励金额的权威来源；仅在没有有效配置时补默认值，
-- 不覆盖运营后台已经保存的配置。奖励券模板使用 INSERT IGNORE，
-- 运营后续修改库存、有效期或停用状态时不会被迁移覆盖。

INSERT INTO referral_config (config)
SELECT JSON_OBJECT(
  'firstOrderPoints', 3,
  'firstOrderCouponAmount', 3,
  'socialStarThreshold', 5,
  'socialStarProduct', '',
  'recommenderThreshold', 10,
  'recommenderRebateRate', 5,
  'inviteCodePrefix', 'WL'
)
WHERE NOT EXISTS (
  SELECT 1
  FROM referral_config
  WHERE deleted = 0
    AND config IS NOT NULL
    AND JSON_LENGTH(config) > 0
);

INSERT IGNORE INTO coupon
  (code, name, type, amount, threshold, brand, scenes, source, description,
   image, validity_type, validity_days, usage_time, stock, status)
VALUES
  ('referral-coupon-3', '分享有礼3元无门槛券', 'REFERRAL', 300, 0,
   '五零时光', '买单', '分享有礼',
   '好友通过邀请链接注册并完成首单，奖励3元无门槛券。',
   '/assets/images/3x/menu-product.jpg', 'DAYS', 15, '00:00:00~23:59:59', 100000, 'enabled');
