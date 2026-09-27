-- =====================================================================
-- V58：门店唯一资源方（一店一资源方）
--
-- 背景：业务要求「一个门店只能绑定一个资源方，一个资源方可以绑定多个门店」。
--   · 旧模型 channel_store 是多对多，无法表达该约束；
--   · 分佣归因 OrderMapper.selectBoundChannel 读 channel_store order by id limit 1，
--     门店改绑后旧行仍在 deleted=0，会把分佣算给旧资源方。
--
-- 方案（已与需求方确认）：
--   · store_profile 增加 channel_subject_id 作为唯一事实源；
--   · channel_store 降级为过渡期兼容镜像，绑定/解绑由应用层同事务双写；
--   · 历史 split_snapshot 不回算。
--
-- 【与初版计划的差异（部署验证阶段修正）】
--   初版用 ORDER BY id LIMIT 1 回填。核查线上库后发现门店 103 存在
--   301/302 两条有效绑定（旧代码改绑遗留），必须按 create_time DESC
--   取最近一次意图，并顺带清理孤儿行。详见下方步骤 2 的说明。
--
-- 幂等：加列/加索引前先判存在，可重复执行。
-- 写法与 V12__concurrency_safety.sql 一致（DELIMITER + 存储过程）。
-- =====================================================================

-- 1) 新增列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用存储过程判存在）
DROP PROCEDURE IF EXISTS v58_add_channel_subject_id;
DELIMITER $$
CREATE PROCEDURE v58_add_channel_subject_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'store_profile'
          AND COLUMN_NAME = 'channel_subject_id'
    ) THEN
        ALTER TABLE store_profile
            ADD COLUMN channel_subject_id BIGINT UNSIGNED NULL
            COMMENT '门店唯一资源方(渠道)主体ID';
    END IF;
END$$
DELIMITER ;
CALL v58_add_channel_subject_id();
DROP PROCEDURE IF EXISTS v58_add_channel_subject_id;

-- 2) 回填：从 channel_store 取每个门店当前有效资源方。
--
--    【线上数据实况（2026-09-27 核查）】
--    旧代码 bindChannelStore 只校验「同一对 (channel, store) 是否重复」，
--    从不校验「该 store 是否已被别的 channel 占用」。运营把 302 从门店 101
--    改绑到门店 103 时，旧代码只 insert 新行、没清旧行，导致门店 103 同时存在
--    301(2026-09-23) 与 302(2026-09-25) 两条 deleted=0 记录。
--
--    【因此回填必须取「最近一次绑定」而不是「最小 id」】
--    create_time DESC 表达的是「保留运营最近一次的绑定意图」；
--    id 顺序只是物理插入顺序，在改绑场景下与意图无关（本例 id=3 反而是后建的）。
UPDATE store_profile sp
SET sp.channel_subject_id = (
    SELECT cs.channel_subject_id
    FROM channel_store cs
    WHERE cs.store_subject_id = sp.subject_id
      AND cs.deleted = 0
    ORDER BY cs.create_time DESC, cs.id DESC
    LIMIT 1
)
WHERE sp.deleted = 0
  AND sp.channel_subject_id IS NULL
  AND EXISTS (
      SELECT 1 FROM channel_store cs
      WHERE cs.store_subject_id = sp.subject_id AND cs.deleted = 0
  );

-- 2.1) 孤儿行对账：把「未被选中」的重复有效行逻辑删除，使镜像表与新事实源一致。
--      只处理同一门店存在多条有效行的冲突情形；不触碰其他行。
--      这些被清理的行就是旧代码改绑时遗留的残留（如门店 103 的 301->103）。
UPDATE channel_store cs
JOIN store_profile sp ON sp.subject_id = cs.store_subject_id
SET cs.deleted = 1
WHERE cs.deleted = 0
  AND sp.deleted = 0
  AND sp.channel_subject_id IS NOT NULL
  AND cs.channel_subject_id <> sp.channel_subject_id;

-- 3) 查询索引：按资源方统计门店是高频读路径。
--    不用 UNIQUE(subject_id, channel_subject_id)：store_profile 已有
--    uk_store_profile_subject(subject_id) 唯一键，单店本就只有一行，
--    「一店一资源方」由该行单列天然保证，复合唯一键属冗余。
DROP PROCEDURE IF EXISTS v58_add_channel_index;
DELIMITER $$
CREATE PROCEDURE v58_add_channel_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'store_profile'
          AND INDEX_NAME = 'idx_store_profile_channel'
    ) THEN
        ALTER TABLE store_profile
            ADD KEY idx_store_profile_channel (channel_subject_id);
    END IF;
END$$
DELIMITER ;
CALL v58_add_channel_index();
DROP PROCEDURE IF EXISTS v58_add_channel_index;
