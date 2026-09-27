-- =====================================================================
-- V56：储值套餐「使用说明」改为全局共用（存 app_config）
--
-- 背景（用户需求）：
--   原实现把使用说明存在 stored_value_package.usage_paragraphs（每套餐一份），
--   运营需要逐个套餐维护，且内容高度重复（退款规则一致）。
--   现改为「所有储值套餐共用一份说明」，存 app_config.stored_value_usage。
--
-- 兼容策略：
--   * stored_value_package.usage_paragraphs 列保留（不删列），
--     但小程序端与后台均改为读全局配置；历史列仅作留档。
--   * app_config 插入用 INSERT ... ON DUPLICATE KEY，可重复执行。
-- =====================================================================

INSERT INTO app_config (config_key, config_name, value, sort, remark)
VALUES ('stored_value_usage', '储值使用说明', JSON_ARRAY(
  '1、本储值套餐包含：储值金额及对应赠送优惠券（满9.9可使用）。',
  '2、储值赠送的券自充值当天起365天有效，请在有效期内尽快使用，单笔订单仅限使用一张优惠券。',
  '3、退款：(1) 成功充值后如有退款需求，可通过小程序「我的」-「联系客服」咨询；(2) 退款时如未使用赠送券，储值金与优惠券完整退回；如已使用，则扣除已用券面额后退回剩余金额。',
  '最终解释权归五零时光所有。'
), 20, '储值套餐共用的使用说明（每行一条）')
ON DUPLICATE KEY UPDATE config_name = VALUES(config_name), remark = VALUES(remark);
