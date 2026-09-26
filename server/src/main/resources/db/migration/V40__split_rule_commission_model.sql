-- =============================================================
-- V40：分账规则改为「固定金额 + 成本直给」模型
--
-- 变更点：
--   1. 门店/资源方比例（store_ratio / channel_ratio）语义改为「每件提成（分）」，
--      不再参与万分比合计；
--   2. 供应商成本、平台提成改由商品明细（cost_price / platform_commission）直取，
--      不再使用 supplier_ratio / platform_ratio 比例；
--   3. 投资人比例 / 达标后比例仍为万分比，投资人达标额必填；
--   4. 作用范围仅保留全局（GLOBAL），停用商品维度（PRODUCT）规则。
--
-- 说明：不改动表结构（列已存在），只重置种子数据语义。
-- =============================================================

-- 1. 软删商品维度（PRODUCT）规则（V9 种子数据，商品模块后续再开发）
UPDATE split_rule SET deleted = 1
WHERE scope = 'PRODUCT' AND deleted = 0;

-- 2. 重置全局默认规则 SR-1000 为新口径（固定金额 + 百分比）
--    门店每件提成 3.00 元（300 分），资源方每件提成 1.00 元（100 分），
--    投资人比例 15%（1500 万分比），达标额 1000.00 元（100000 分），达标后 20%（2000 万分比）。
--    platform_ratio / supplier_ratio 新模型下不再参与计算，置 0 仅作兼容保留。
UPDATE split_rule SET
    platform_ratio  = 0,
    store_ratio     = 300,
    channel_ratio   = 100,
    investor_ratio  = 1500,
    supplier_ratio  = 0,
    investor_threshold_amount = 100000,
    investor_ratio_after      = 2000
WHERE code = 'SR-1000' AND scope = 'GLOBAL' AND deleted = 0;

-- 3. 兜底：任何历史全局规则若达标额仍为 0（旧「不启用」语义），
--    填一个默认正数以满足「必填」约束，避免后台编辑时被拦截。
UPDATE split_rule SET investor_threshold_amount = 100000
WHERE scope = 'GLOBAL' AND deleted = 0 AND (investor_threshold_amount IS NULL OR investor_threshold_amount <= 0);