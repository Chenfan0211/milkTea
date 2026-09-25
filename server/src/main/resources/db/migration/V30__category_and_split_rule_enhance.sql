-- =============================================================
-- 分类管理 & 分账规则：补齐后台页面所需字段
--
-- 背景：后台「分类管理」页需要维护「状态」与「分类标签」，但 product_category
--       原本只有 code/name/type/sort，前端提交的 tag/enabled 被通用 CRUD
--       白名单静默丢弃（接口仍返回 200），表现为「填了数据页面不显示」；
--       「分账规则」页需要配置「投资人当月达标后比例」，split_rule 亦无对应列。
--
-- 影响面：均为新增列，不改动既有列类型与含义，对存量业务无破坏性影响。
-- =============================================================

-- ---------- 1. 分类管理：补 tag / enabled ----------
ALTER TABLE product_category
    ADD COLUMN tag VARCHAR(64) NULL COMMENT '分类标签' AFTER name,
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用' AFTER sort;

-- 历史数据：存量 8 条 sort 全为 0，order by sort asc 下顺序不稳定，
-- 按 id 回填初始顺序（仅影响 sort=0 的行，不覆盖已手工排序的数据）
UPDATE product_category SET sort = id WHERE sort = 0;

-- ---------- 2. 分账规则：补投资人阈值配置 ----------
-- investor_threshold_amount：投资人当月累计分账达标额（分），0 = 不启用阈值规则
-- investor_ratio_after：达标后投资人比例（万分比）
ALTER TABLE split_rule
    ADD COLUMN investor_threshold_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '投资人当月累计分账达标额（分），0=不启用阈值规则' AFTER investor_ratio,
    ADD COLUMN investor_ratio_after INT NOT NULL DEFAULT 0
        COMMENT '达标后投资人比例（万分比）' AFTER investor_threshold_amount;

-- 历史数据回填：默认「不启用阈值」，达标比例沿用原比例，
-- 保证升级前后分账金额完全一致（阈值=0 时引擎始终走 investor_ratio）
UPDATE split_rule SET investor_ratio_after = investor_ratio
    WHERE investor_threshold_amount = 0;

-- ---------- 3. 激活分账规则页新增列的白名单说明（见 CrudRegistry.java） ----------
-- 注意：仅加列不够，还需在 server 侧 CrudRegistry 的 splitRules / productCategories
--       白名单中放行这些字段，否则写入仍会被 CrudService.filterWritable 静默丢弃。
