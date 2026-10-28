-- =====================================================================
-- V93: 放宽 biz_subject.bound_user_id 唯一约束，支持一用户多经营角色
--
-- 背景：
--   V68 为 biz_subject.bound_user_id 加了 UNIQUE KEY，约束「一个用户
--   只能被一个主体绑定」。该约束在「一用户仅一个经营角色」时成立，
--   但与「团长可与门店/资源方/投资人共存」的设计冲突：
--   多角色用户重新绑定第二个主体时，会撞
--   Duplicate entry for key 'uk_biz_subject_bound_user'（HTTP 500）。
--
-- 现状确认（生产）：
--   用户 1 同时持有 STORE/INVESTOR/CHANNEL 三角色；
--   团长（LEADER）要求在保留既有主体（bound_subject_id=101）的前提下新增主体。
--
-- 处理：
--   1. 删除该唯一索引，改为普通索引（保留按用户反查主体的能力）；
--   2. 用户侧「主主体」仍由 app_user.bound_subject_id 单值表达，语义不变；
--   3. 「一店多绑」防护仍由应用层 bindUserRole/bindPair 的
--      「主体已绑定其他用户」校验保证（不是靠该唯一键）。
-- =====================================================================

SET @exist := (SELECT COUNT(*) FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'biz_subject'
                 AND INDEX_NAME = 'uk_biz_subject_bound_user');
SET @ddl := IF(@exist > 0,
    'ALTER TABLE biz_subject DROP INDEX uk_biz_subject_bound_user',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 改为普通索引：多角色场景下同一用户会出现在多行，索引仍需支持反查
SET @exist2 := (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'biz_subject'
                  AND INDEX_NAME = 'idx_biz_subject_bound_user');
SET @ddl2 := IF(@exist2 = 0,
    'ALTER TABLE biz_subject ADD INDEX idx_biz_subject_bound_user (bound_user_id)',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
