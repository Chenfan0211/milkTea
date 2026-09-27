-- =====================================================================
-- V61：会员成长值（累计消费）字段与流水表
--
-- 背景：
--   会员成长值 = 累计消费金额（1 元 = 1 成长值），等级由累计消费判定。
--   但 app_user 表此前没有「累计消费」字段，前端 member-level.js 依赖的
--   profile.totalSpend 恒为 0，导致任何订单（含储值余额支付）支付后
--   成长值都不变化 —— 这是功能缺失，而非显示问题。
--
-- 目标：
--   1) app_user 增加 total_spend（累计消费，单位：分），默认 0；
--   2) 新增 growth_record 流水表，order_no 唯一索引做幂等闸门；
--   3) 由 marketing 服务消费「订单支付成功」事件时原子累加（只算点单消费）。
--
-- 幂等：与 V59/V60 一致，用 information_schema 判断后动态执行。
-- =====================================================================

SET @tbl := 'app_user';

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'total_spend');
SET @sql := IF(@exist = 0,
  'ALTER TABLE app_user ADD COLUMN total_spend BIGINT NOT NULL DEFAULT 0 COMMENT ''累计消费金额(分)，会员成长值'' AFTER balance',
  'SELECT ''total_spend 已存在，跳过'' AS message');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 成长值流水表（幂等：order_no 唯一）
CREATE TABLE IF NOT EXISTS growth_record (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id     BIGINT UNSIGNED NOT NULL,
    order_no    VARCHAR(64) NOT NULL,
    amount      BIGINT NOT NULL DEFAULT 0 COMMENT '本次累加金额(分)',
    total_after BIGINT NOT NULL DEFAULT 0 COMMENT '累加后累计消费(分)',
    source      VARCHAR(32) NOT NULL DEFAULT 'ORDER_PAID' COMMENT '来源',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_growth_record_order (order_no),
    KEY idx_growth_record_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成长值流水（累计消费）';
