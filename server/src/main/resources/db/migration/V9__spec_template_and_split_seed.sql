-- =============================================================
-- 第3批：规格组模板初始化 + 分账规则种子
-- 说明：
-- - spec_group_template / spec_option_template 为「规格组模板」（如 杯型：中杯/大杯）
--   与 product_spec（商品级规格明细）语义不同，互不影响；
-- - split_rule 统一使用万分比（合计 10000），与后端分账引擎口径一致。
-- =============================================================

-- ---------- 规格组模板 ----------
INSERT INTO spec_group_template (id, code, name, sort) VALUES
(1, 'size', '份量', 1),
(2, 'temperature', '温度', 2),
(3, 'sugar', '甜度', 3),
(4, 'topping', '加料', 4);

INSERT INTO spec_option_template (group_id, code, name, price_delta, icon, sort) VALUES
-- 份量
(1, 'medium', '中杯', 0, NULL, 1),
(1, 'large', '大杯', 300, NULL, 2),
-- 温度
(2, 'standard-ice', '标准冰', 0, 'star', 1),
(2, 'less-ice', '少冰', 0, NULL, 2),
(2, 'no-ice', '去冰（微凉）', 0, NULL, 3),
(2, 'hot', '热饮', 0, NULL, 4),
-- 甜度
(3, 'no-sugar', '不另外加糖', 0, NULL, 1),
(3, 'light-sugar', '少糖', 0, NULL, 2),
(3, 'half-sugar', '半糖', 0, NULL, 3),
(3, 'full-sugar', '全糖', 0, NULL, 4),
-- 加料
(4, 'none', '不加料', 0, NULL, 1),
(4, 'horseshoe', '加马蹄粉圆', 200, NULL, 2),
(4, 'pearl', '加珍珠', 200, NULL, 3);

-- ---------- 分账规则（万分比，五方合计 = 10000） ----------
-- 默认全局规则 SR-1000 已由 V3 初始化（1000/5000/1500/1500/1000）
-- 这里补充两条示例规则，便于后台「分账规则」页演示与切换
INSERT INTO split_rule (code, name, scope, platform_ratio, store_ratio, channel_ratio, investor_ratio, supplier_ratio, status) VALUES
('SR-1001', '高毛利商品分账', 'PRODUCT', 1500, 4500, 1000, 1500, 1500, 'disabled'),
('SR-1002', '渠道专享分账',   'PRODUCT', 1000, 4500, 2500, 1000, 1000, 'disabled');
