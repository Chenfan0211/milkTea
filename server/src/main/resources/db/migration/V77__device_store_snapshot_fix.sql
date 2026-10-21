-- =============================================================
-- V77：设备表门店字段口径修正（慧养智萃茶机对接，补齐 V75 遗漏）
--
-- 背景（2026-10-14 设备对接复核发现的两处口径问题）：
--
--   1) device_store_binding.store_subject_id 在 V75 里是 NOT NULL，
--      而 DeviceService#unbindStore / #bindStore 在「门店已被逻辑删除」
--      （findSubject 返回 null）时会写入 NULL 作为历史快照。
--      两者冲突 -> 插库报 NOT NULL 约束 -> 接口 500，
--      设备一旦挂在已删门店上就再也解绑/换绑不了，只能改库。
--      历史行只需要 store_name 快照即可回溯，故改为允许 NULL。
--
--   2) 三张设备表的 store_name 是 VARCHAR(64)，而 biz_subject.name 是
--      VARCHAR(128)。DeviceService 写 store_name 时不做截断，
--      门店名超过 64 字符会触发 "Data too long" 并回滚整个事务，
--      冲泡记录/状态消息因此反复重试并最终进死信队列。
--      这里统一放宽到 VARCHAR(128)，与 biz_subject.name 对齐。
--
-- 【为什么新建迁移而不是改 V75】
--   V75 一旦在任何环境（本地/测试/生产）被 Flyway 应用，
--   修改其内容会导致 checksum 校验失败、服务无法启动
--   （与 2026-09-22 生产事故同类风险）。因此口径修正一律走新迁移。
--
-- 【幂等】用 information_schema 判断后再改，可重复执行。
-- =============================================================

-- ---------- 1. device_store_binding.store_subject_id 允许 NULL ----------
-- 语义：门店被删除后，解绑/换绑历史仍要留痕，此时只保留 store_name 快照，
--       store_subject_id 置 NULL，不再阻断写入。
SET @sql = (SELECT IF(
    (SELECT IS_NULLABLE FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'device_store_binding'
       AND COLUMN_NAME = 'store_subject_id') = 'NO',
    'ALTER TABLE device_store_binding MODIFY COLUMN store_subject_id BIGINT UNSIGNED NULL COMMENT ''门店主体 biz_subject.id；门店被删除时为空，仅保留 store_name 快照''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 三张表的 store_name 放宽到 VARCHAR(128) ----------
-- 与 biz_subject.name 同宽，避免门店名过长时写入失败并拖垮整条消费链路。
SET @sql = (SELECT IF(
    (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'device_store_binding'
       AND COLUMN_NAME = 'store_name') < 128,
    'ALTER TABLE device_store_binding MODIFY COLUMN store_name VARCHAR(128) NULL COMMENT ''绑定时刻门店名快照（与 biz_subject.name 同宽）''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'device_brew_record'
       AND COLUMN_NAME = 'store_name') < 128,
    'ALTER TABLE device_brew_record MODIFY COLUMN store_name VARCHAR(128) NULL COMMENT ''写入时刻门店名快照（与 biz_subject.name 同宽）''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(
    (SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'device_status_log'
       AND COLUMN_NAME = 'store_name') < 128,
    'ALTER TABLE device_status_log MODIFY COLUMN store_name VARCHAR(128) NULL COMMENT ''写入时刻门店名快照（与 biz_subject.name 同宽）''',
    'SELECT 1'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
