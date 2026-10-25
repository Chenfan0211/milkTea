-- =====================================================================
-- V81：营销订阅消息、系统门店上下文与储值赠券多张支持
--
-- 本迁移为向前兼容增量：
--   1. 储值订单保存门店 subjectId，供订阅通知解析系统门店名称；
--   2. 发送幂等账本，防止重复回调/定时任务重复发送；
--   3. user_coupon 增加 grant_no，支持同一用户同一模板的多张赠券，
--      普通领券/邀请奖励/积分兑换仍使用 SINGLE 保持原有去重；
--   4. 我的页功能宫格增加“消息订阅”入口。
-- =====================================================================

-- ---------- 1. stored_value_order.store_subject_id ----------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'stored_value_order'
                 AND COLUMN_NAME = 'store_subject_id');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE stored_value_order ADD COLUMN store_subject_id BIGINT UNSIGNED NULL COMMENT ''充值门店 subjectId（biz_subject.id）'' AFTER package_id',
    'SELECT ''stored_value_order.store_subject_id 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. 订阅消息发送幂等账本 ----------
CREATE TABLE IF NOT EXISTS subscribe_message_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    message_key   VARCHAR(191) NOT NULL COMMENT '业务幂等键',
    template_key  VARCHAR(64)  NOT NULL COMMENT '模板业务标识',
    user_id       BIGINT UNSIGNED NOT NULL COMMENT '接收用户ID',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SENT/SKIPPED/FAILED',
    errcode       INT          NULL COMMENT '微信错误码',
    errmsg        VARCHAR(500) NULL COMMENT '微信错误信息或跳过原因',
    attempts      INT          NOT NULL DEFAULT 1 COMMENT '已尝试次数',
    sent_at       DATETIME     NULL COMMENT '成功发送时间',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_subscribe_message_log_key (message_key),
    KEY idx_subscribe_message_log_status (status, update_time),
    KEY idx_subscribe_message_log_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='微信订阅消息发送幂等账本';

-- ---------- 3. user_coupon.grant_no ----------
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'user_coupon'
                 AND COLUMN_NAME = 'grant_no');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE user_coupon ADD COLUMN grant_no VARCHAR(96) NOT NULL DEFAULT ''SINGLE'' COMMENT ''发放幂等/分组键；普通领券=SINGLE，储值赠券=SV:订单号:券ID:序号'' AFTER source',
    'SELECT ''user_coupon.grant_no 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE user_coupon
   SET grant_no = 'SINGLE'
 WHERE grant_no IS NULL OR grant_no = '';

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'user_coupon'
                 AND INDEX_NAME = 'uk_user_coupon_active');
SET @ddl := IF(@exist > 0,
    'ALTER TABLE user_coupon DROP INDEX uk_user_coupon_active',
    'SELECT ''uk_user_coupon_active 不存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'user_coupon'
                 AND INDEX_NAME = 'uk_user_coupon_active_grant');
SET @ddl := IF(@exist = 0,
    'ALTER TABLE user_coupon ADD UNIQUE KEY uk_user_coupon_active_grant (user_id, active_coupon_id, grant_no)',
    'SELECT ''uk_user_coupon_active_grant 已存在，跳过'' AS message');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. 我的页功能入口 ----------
UPDATE app_config
   SET value = JSON_ARRAY_APPEND(
       value,
       '$',
       JSON_OBJECT(
           'id', 'subscribe-message',
           'label', '消息订阅',
           'icon', '/assets/icons/lucide/bell.svg'
       )
   )
 WHERE config_key = 'profile_functions'
   AND deleted = 0
   AND NOT JSON_CONTAINS(value, JSON_OBJECT('id', 'subscribe-message'));
