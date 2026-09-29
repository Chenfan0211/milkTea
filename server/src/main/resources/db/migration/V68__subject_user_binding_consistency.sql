-- =============================================================
-- V66：用户 ↔ 主体 绑定关系一致性修复 + 1:1 唯一约束
--
-- 背景（真实数据不一致）：
--   app_user.bound_subject_id 与 biz_subject.bound_user_id 是「同一关系」的两个方向，
--   由 /admin/subject/binding 双向写入。但历史上两处写入缺少一致性校验与旧值清理，
--   导致：
--     1) 门店换绑用户后，旧用户的 bound_subject_id 未清空（漂移）；
--     2) 一个用户可被多个门店的 bound_user_id 指向（多绑）。
--   表现为「门店管理页显示的用户」与「用户列表显示的绑定主体」对不上。
--
-- 修复口径（用户决策）：以 biz_subject.bound_user_id 为准（门店侧是运营主操作入口），
--   反推重建 app_user.bound_subject_id；同一用户被多个主体指向时，保留 id 最小的主体，
--   其余主体解绑（bound_user_id 置空），保证 1:1。
--
-- 约束：修复后为两个方向各加唯一索引（1:1 硬约束）。
--   · 唯一索引允许多个 NULL（未绑定），符合「可空但非空时唯一」的语义；
--   · 必须先修复数据再加索引，否则历史脏数据会导致建索引失败。
--
-- 幂等：用 information_schema 判断索引是否存在后再执行。
-- =============================================================

-- ---------- 1. 先清空全部 app_user.bound_subject_id，随后按门店侧重建 ----------
-- 说明：直接重建比「逐条比对修复」更简单可靠，且门店侧是权威源。
UPDATE app_user SET bound_subject_id = NULL WHERE bound_subject_id IS NOT NULL;

-- ---------- 2. 处理「一个用户被多个主体指向」：保留 id 最小的主体，其余解绑 ----------
UPDATE biz_subject s
JOIN (
    SELECT bound_user_id, MIN(id) AS keep_id
      FROM biz_subject
     WHERE bound_user_id IS NOT NULL AND deleted = 0
     GROUP BY bound_user_id
    HAVING COUNT(*) > 1
) dup ON dup.bound_user_id = s.bound_user_id AND s.id <> dup.keep_id
SET s.bound_user_id = NULL;

-- ---------- 3. 以 biz_subject.bound_user_id 为准，重建 app_user.bound_subject_id ----------
UPDATE app_user u
JOIN biz_subject s ON s.bound_user_id = u.id AND s.deleted = 0
SET u.bound_subject_id = s.id
WHERE u.deleted = 0;

-- ---------- 4. 清理「app_user 指向的主体的 bound_user_id 不是自己」的残留 ----------
-- 理论上第 3 步已保证一致，这里作为兜底（如 u.bound_subject_id 指向已被软删的主体）。
UPDATE app_user u
LEFT JOIN biz_subject s ON s.id = u.bound_subject_id AND s.deleted = 0 AND s.bound_user_id = u.id
SET u.bound_subject_id = NULL
WHERE u.bound_subject_id IS NOT NULL AND s.id IS NULL;

-- ---------- 5. biz_subject.bound_user_id 唯一索引（一个用户只能绑一个主体） ----------
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'biz_subject'
               AND INDEX_NAME = 'uk_biz_subject_bound_user');
SET @sql := IF(@idx = 0,
  'ALTER TABLE biz_subject ADD UNIQUE KEY uk_biz_subject_bound_user (bound_user_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6. app_user.bound_subject_id 唯一索引（一个主体只能绑一个用户） ----------
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_user'
               AND INDEX_NAME = 'uk_app_user_bound_subject');
SET @sql := IF(@idx = 0,
  'ALTER TABLE app_user ADD UNIQUE KEY uk_app_user_bound_subject (bound_subject_id)',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
