-- =============================================================
-- V78：设备状态流水补 raw_payload 留档（慧养智萃茶机对接）
--
-- 背景（2026-10-15 复核发现的留档缺口）：
--   冲泡记录（device_brew_record.raw_payload）保留了原始 payload，
--   但状态流水（device_status_log）没有 —— 对方日后给 status 报文
--   增加字段（如信号强度、故障码）时，历史数据无从回溯，
--   只能靠 device_message_error（那只是坏报文表，正常报文不会进去）。
--
-- 口径：两类业务报文都把原始 payload 原样留档，报文加字段时前端/后端再从容补齐。
--
-- 【为什么新建迁移而不是改 V75】
--   V75 已在任何环境被 Flyway 应用过，修改其内容会导致 checksum 校验失败、
--   服务无法启动（与 2026-09-22 生产事故同类风险）。结构变更一律走新迁移。
--
-- 【幂等】用 information_schema 判断列是否存在，可重复执行。
-- =============================================================

SET @sql = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'device_status_log'
       AND COLUMN_NAME = 'raw_payload') = 0,
    'ALTER TABLE device_status_log ADD COLUMN raw_payload TEXT NULL COMMENT ''原始 payload（报文 JSON 文本，便于对方加字段后回溯）'' AFTER base_create_time',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;