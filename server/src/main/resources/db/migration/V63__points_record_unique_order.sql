-- =====================================================================
-- V63：时光币消费发放幂等闸门
--
-- 背景：
--   后台积分规则配置了「每消费 1 元 +1 时光币」（points_earning_rule
--   code=consume, reward_type=per-yuan），但后端从未实现消费后发放时光币
--   的逻辑。本迁移为其补齐幂等闸门。
--
-- 目标：
--   给 points_record 增加 (order_no, source) 唯一索引，作为「同一订单
--   同一来源只发一次时光币」的幂等键。消费发放 source='consume'，
--   重复投递同一订单的支付成功事件时，插入流水会抛 DuplicateKeyException，
--   由消费端捕获后直接 ACK，不重复发币。
--
-- 注意：source 允许为 NULL（历史流水 / 非订单来源），唯一索引对 NULL 不生效，
--       因此不影响既有数据。仅当 (order_no, source) 同时非空时才唯一。
-- 幂等：用 information_schema 判断索引是否存在后动态执行。
-- =====================================================================

SET @idx := 'uk_points_record_order';

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'points_record' AND INDEX_NAME = @idx);

SET @sql := IF(@exist = 0,
  'ALTER TABLE points_record ADD UNIQUE KEY uk_points_record_order (order_no, source)',
  'SELECT ''uk_points_record_order 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;