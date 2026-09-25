-- =============================================================
-- V22：商品成本/分佣字段 + 商品分类关联修正 + 全页测试数据
--
-- 目标（2026-09-23）：
--   1. product 增加 cost_price / platform_commission 两列（单位：分）；
--   2. 修正商品与分类的关联，并消除跨分类重名；
--   3. 为运营后台各页面补充可辨识、可清理的测试数据。
--
-- 数据规范（便于后续测试）：
--   · 演示数据统一带 DEMO- 前缀（编号类字段），与真实业务数据区分；
--   · 金额单位统一为「分」；
--   · 枚举值对齐后端 Java 定义；
--   · 全部幂等：可重复执行，不报错、不重复插入（见文件末清理 SQL）。
--
-- 依赖的既有种子数据：
--   subject:  101~105 门店 / 201~203 投资人 / 301~303 资源方 / 401~403 供应商 / 1 平台
--   category: 3 草本养生茶 / 5 传统原叶茶 / 8 季节限定（CATEGORY 层）
-- =============================================================

-- ---------- 1. product 新增两列（成本价 / 平台分佣）----------
-- MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，故用 information_schema 判断后动态执行。
SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'cost_price');
SET @sql := IF(@exist = 0,
  'ALTER TABLE product ADD COLUMN cost_price BIGINT NOT NULL DEFAULT 0 COMMENT ''成本价（分）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product' AND COLUMN_NAME = 'platform_commission');
SET @sql := IF(@exist = 0,
  'ALTER TABLE product ADD COLUMN platform_commission BIGINT NOT NULL DEFAULT 0 COMMENT ''平台分佣（分）''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 消除跨分类重名 ----------
-- classic-001 与 featured-001 原均名为「五窨茉莉抹茶」；
-- 保留 classic-001 原名，featured-001 改为「镇店茉白」，避免列表难以区分。
UPDATE product SET name = '镇店茉白'
WHERE product_id = 'featured-001' AND name = '五窨茉莉抹茶';

-- ---------- 3. 为既有商品回填成本价 / 平台分佣 ----------
-- 规则：成本价 = 原价 40%；平台分佣 = 售价 10%（仅演示用途，四舍五入到分）。
UPDATE product SET
  cost_price = ROUND(original_price * 0.4),
  platform_commission = ROUND(price * 0.1)
WHERE deleted = 0 AND (cost_price = 0 OR platform_commission = 0);

-- =============================================================
-- 4. 全页测试数据（每页 10~20 条）
--    统一使用 DEMO- 前缀，可整体清理（见文件末）
-- =============================================================

-- ---------- 4.1 商品：补充到 30 条，并按语义铺到各叶子分类 ----------
-- 说明：既有 18 条商品全部落在 category_id = 3 / 5 / 8，
--      这里补充 12 条 DEMO 商品，使分类分布更贴近真实运营。
--      分类层级：TAB(1,6) -> GROUP(2,4,7) -> CATEGORY(3,5,8)，商品挂在 CATEGORY 层。
INSERT INTO product
  (product_id, code, name, category_id, tags, description, price, original_price,
   cost_price, platform_commission, image, on_sale, split_rule_id)
VALUES
  ('DEMO-P001', 'DEMO-1001', '【演示】茉莉毛尖冷萃',   3, JSON_ARRAY('演示','冷萃'), '演示商品：用于后台列表与筛选测试', 1800, 2200, 880, 180, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P002', 'DEMO-1002', '【演示】陈皮白茶',       3, JSON_ARRAY('演示','养生'), '演示商品：用于后台列表与筛选测试', 1600, 2000, 800, 160, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P003', 'DEMO-1003', '【演示】桂花乌龙奶盖',   3, JSON_ARRAY('演示','奶盖'), '演示商品：用于后台列表与筛选测试', 2100, 2600, 1040, 210, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P004', 'DEMO-1004', '【演示】玫瑰洛神冰茶',   3, JSON_ARRAY('演示','花茶'), '演示商品：用于后台列表与筛选测试', 1700, 2100, 840, 170, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P005', 'DEMO-1005', '【演示】蜜桃乌龙原叶',   5, JSON_ARRAY('演示','原叶'), '演示商品：用于后台列表与筛选测试', 1900, 2400, 960, 190, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P006', 'DEMO-1006', '【演示】铁观音轻乳',     5, JSON_ARRAY('演示','原叶'), '演示商品：用于后台列表与筛选测试', 2000, 2500, 1000, 200, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P007', 'DEMO-1007', '【演示】凤凰单丛冷泡',   5, JSON_ARRAY('演示','冷泡'), '演示商品：用于后台列表与筛选测试', 2300, 2800, 1120, 230, '/assets/images/3x/menu-product.jpg', 0, 1),
  ('DEMO-P008', 'DEMO-1008', '【演示】黄山毛峰春茶',   5, JSON_ARRAY('演示','春茶'), '演示商品：用于后台列表与筛选测试', 2200, 2700, 1080, 220, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P009', 'DEMO-1009', '【演示】杨枝甘露限定',   8, JSON_ARRAY('演示','季节'), '演示商品：用于后台列表与筛选测试', 2400, 3000, 1200, 240, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P010', 'DEMO-1010', '【演示】栗子拿铁暖饮',   8, JSON_ARRAY('演示','季节'), '演示商品：用于后台列表与筛选测试', 2500, 3100, 1240, 250, '/assets/images/3x/menu-product.jpg', 1, 1),
  ('DEMO-P011', 'DEMO-1011', '【演示】雪梨茉莉润燥',   8, JSON_ARRAY('演示','季节'), '演示商品：用于后台列表与筛选测试', 1900, 2400, 960, 190, '/assets/images/3x/menu-product.jpg', 0, NULL),
  ('DEMO-P012', 'DEMO-1012', '【演示】青梅冷萃气泡',   8, JSON_ARRAY('演示','季节'), '演示商品：用于后台列表与筛选测试', 2100, 2600, 1040, 210, '/assets/images/3x/menu-product.jpg', 1, 1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), category_id = VALUES(category_id), tags = VALUES(tags),
  description = VALUES(description), price = VALUES(price), original_price = VALUES(original_price),
  cost_price = VALUES(cost_price), platform_commission = VALUES(platform_commission),
  on_sale = VALUES(on_sale), deleted = 0;

-- 演示商品上架到全部门店（与既有商品一致：101~105）
INSERT INTO product_store (product_id, store_subject_id)
SELECT p.id, s.id
FROM product p
CROSS JOIN (SELECT 101 AS id UNION ALL SELECT 102 UNION ALL SELECT 103
            UNION ALL SELECT 104 UNION ALL SELECT 105) s
WHERE p.product_id LIKE 'DEMO-P%' AND p.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM product_store ps
    WHERE ps.product_id = p.id AND ps.store_subject_id = s.id
  );

-- ---------- 4.2 商品规格（product_spec）：给演示商品补规格组 ----------
-- 既有 18 条商品已有规格；这里为 DEMO 商品补「容量 / 冰量」两组，便于规格展示与编辑测试。
INSERT INTO product_spec (product_id, group_code, group_label, option_code, option_label, price_delta, selected, sort)
SELECT p.id, g.gc, g.gl, g.oc, g.ol, g.pd, g.sel, g.so
FROM product p
CROSS JOIN (
  SELECT 'size' AS gc, '容量' AS gl, 'medium' AS oc, '中杯' AS ol, 0 AS pd, 1 AS sel, 0 AS so
  UNION ALL SELECT 'size', '容量', 'large', '大杯', 300, 0, 1
  UNION ALL SELECT 'ice', '冰量', 'less-ice', '少冰', 0, 1, 2
  UNION ALL SELECT 'ice', '冰量', 'no-ice', '去冰', 0, 0, 3
) g
WHERE p.product_id LIKE 'DEMO-P%' AND p.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM product_spec ps
    WHERE ps.product_id = p.id AND ps.group_code = g.gc AND ps.option_code = g.oc
  );

-- ---------- 4.3 订单（orders）：补足到 20 条，覆盖多状态 ----------
INSERT INTO orders
  (order_no, user_id, store_subject_id, meal_type, pickup_time, status, pay_status,
   pickup_code, total_amount, original_amount, discount_amount, paid_amount,
   coupon_discount, points_used, points_earned, create_time, pay_time)
VALUES
  ('DEMO-O20260001', 1, 101, 'TAKEOUT', NOW(), 'COMPLETED', 'PAID', 'DEMO-1001', 1800, 2200, 400, 1800, 0, 0, 18, NOW() - INTERVAL 9 DAY, NOW() - INTERVAL 9 DAY),
  ('DEMO-O20260002', 1, 102, 'TAKEOUT', NOW(), 'COMPLETED', 'PAID', 'DEMO-1002', 2000, 2500, 500, 2000, 0, 0, 20, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 8 DAY),
  ('DEMO-O20260003', 2, 103, 'TAKEOUT', NOW(), 'VERIFIED',  'PAID', 'DEMO-1003', 2100, 2600, 500, 2100, 0, 0, 21, NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 7 DAY),
  ('DEMO-O20260004', 2, 104, 'TAKEOUT', NOW(), 'VERIFIED',  'PAID', 'DEMO-1004', 2400, 3000, 600, 2400, 0, 0, 24, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY),
  ('DEMO-O20260005', 7, 105, 'TAKEOUT', NOW(), 'PAID',      'PAID', 'DEMO-1005', 1900, 2400, 500, 1900, 0, 0, 19, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY),
  ('DEMO-O20260006', 7, 101, 'TAKEOUT', NOW(), 'PAID',      'PAID', 'DEMO-1006', 2200, 2700, 500, 2200, 0, 0, 22, NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY),
  ('DEMO-O20260007', 1, 102, 'TAKEOUT', NOW(), 'CREATED',   'UNPAID', 'DEMO-1007', 1600, 2000, 400, 0, 0, 0, 0, NOW() - INTERVAL 4 DAY, NULL),
  ('DEMO-O20260008', 2, 103, 'TAKEOUT', NOW(), 'CREATED',   'UNPAID', 'DEMO-1008', 2300, 2800, 500, 0, 0, 0, 0, NOW() - INTERVAL 4 DAY, NULL),
  ('DEMO-O20260009', 7, 104, 'TAKEOUT', NOW(), 'CANCELED',  'UNPAID', 'DEMO-1009', 1700, 2100, 400, 0, 0, 0, 0, NOW() - INTERVAL 3 DAY, NULL),
  ('DEMO-O20260010', 1, 105, 'TAKEOUT', NOW(), 'COMPLETED', 'PAID', 'DEMO-1010', 2500, 3100, 600, 2500, 0, 0, 25, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY)
ON DUPLICATE KEY UPDATE
  status = VALUES(status), pay_status = VALUES(pay_status), paid_amount = VALUES(paid_amount),
  total_amount = VALUES(total_amount), original_amount = VALUES(original_amount),
  discount_amount = VALUES(discount_amount), deleted = 0;

-- ---------- 4.4 支付单（payment）：为已支付订单补支付记录 ----------
INSERT INTO payment
  (payment_no, order_id, order_no, amount, channel, third_status, standard_status,
   transaction_id, payer_openid, create_time, callback_time)
SELECT CONCAT('DEMO-PAY-', SUBSTRING(o.order_no, 6)), o.id, o.order_no, o.paid_amount,
       'wxpay', 'SUCCESS', 'SUCCESS',
       CONCAT('DEMO-TXN-', SUBSTRING(o.order_no, 6)), 'DEMO-OPENID',
       o.create_time, o.pay_time
FROM orders o
WHERE o.order_no LIKE 'DEMO-O%' AND o.pay_status = 'PAID' AND o.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM payment p WHERE p.order_no = o.order_no);

-- ---------- 4.5 退款（refund）：覆盖申请中/已通过/已驳回 ----------
INSERT INTO refund
  (refund_no, order_id, order_no, amount, status, reason, apply_time, review_time, complete_time, create_time)
SELECT CONCAT('DEMO-RF-', SUBSTRING(o.order_no, 6)), o.id, o.order_no, o.paid_amount,
       CASE MOD(o.id, 3) WHEN 0 THEN 'APPLIED' WHEN 1 THEN 'APPROVED' ELSE 'REJECTED' END,
       '演示退款：用于退款管理页测试',
       o.pay_time, IF(MOD(o.id, 3) = 0, NULL, o.pay_time + INTERVAL 1 DAY),
       IF(MOD(o.id, 3) = 1, o.pay_time + INTERVAL 2 DAY, NULL), o.pay_time
FROM orders o
WHERE o.order_no LIKE 'DEMO-O%' AND o.pay_status = 'PAID' AND o.deleted = 0
  AND MOD(o.id, 2) = 0
  AND NOT EXISTS (SELECT 1 FROM refund r WHERE r.order_no = o.order_no);

-- ---------- 4.6 核销记录（verify_record）----------
INSERT INTO verify_record
  (verify_code, order_id, order_no, type, store_subject_id, operator, device, result, create_time)
SELECT CONCAT('DEMO-VC-', SUBSTRING(o.order_no, 6)), o.id, o.order_no, 'ORDER',
       o.store_subject_id, 'DEMO-门店店员', 'DEMO-POS-01',
       CASE MOD(o.id, 4) WHEN 0 THEN 'FAIL' ELSE 'SUCCESS' END,
       COALESCE(o.verify_time, o.create_time + INTERVAL 1 HOUR)
FROM orders o
WHERE o.order_no LIKE 'DEMO-O%' AND o.status IN ('VERIFIED', 'COMPLETED') AND o.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM verify_record v WHERE v.order_no = o.order_no);

-- ---------- 4.7 评论（comments）：含待审核/通过/驳回 ----------
INSERT INTO comments (order_id, user_id, rating, content, status, review_time, create_time)
SELECT o.id, o.user_id, 4 + MOD(o.id, 2),
       CONCAT('【演示评论】', o.order_no, '：口味稳定，出餐速度可以。'),
       CASE MOD(o.id, 3) WHEN 0 THEN 'PENDING' WHEN 1 THEN 'APPROVED' ELSE 'REJECTED' END,
       IF(MOD(o.id, 3) = 0, NULL, o.create_time + INTERVAL 1 DAY),
       o.create_time + INTERVAL 2 HOUR
FROM orders o
WHERE o.order_no LIKE 'DEMO-O%' AND o.status IN ('VERIFIED', 'COMPLETED') AND o.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.order_id = o.id);

-- ---------- 4.8 会员等级（member_level）：补足到 10 档 ----------
INSERT INTO member_level (level_code, name, amount_target, discount, benefits, sort)
VALUES
  ('DEMO-L5',  '【演示】黑金卡',   500000, '0.85', JSON_ARRAY('演示权益：专属客服'), 5),
  ('DEMO-L6',  '【演示】钻石卡',   800000, '0.82', JSON_ARRAY('演示权益：生日礼遇'), 6),
  ('DEMO-L7',  '【演示】至尊卡',  1200000, '0.80', JSON_ARRAY('演示权益：优先出餐'), 7),
  ('DEMO-L8',  '【演示】联名卡',  1500000, '0.78', JSON_ARRAY('演示权益：联名赠品'), 8),
  ('DEMO-L9',  '【演示】企业卡',  2000000, '0.75', JSON_ARRAY('演示权益：对公开票'), 9),
  ('DEMO-L10', '【演示】创始会员', 3000000, '0.70', JSON_ARRAY('演示权益：新品试饮'), 10)
ON DUPLICATE KEY UPDATE
  name = VALUES(name), amount_target = VALUES(amount_target),
  discount = VALUES(discount), benefits = VALUES(benefits), sort = VALUES(sort), deleted = 0;

-- ---------- 4.9 用户优惠券（user_coupon）：补足到 15 条 ----------
INSERT INTO user_coupon (user_id, coupon_id, status, receive_time, use_time)
SELECT u.uid, c.cid, s.st, NOW() - INTERVAL (u.uid + c.cid) DAY,
       IF(s.st = 'USED', NOW() - INTERVAL 1 DAY, NULL)
FROM (SELECT 1 AS uid UNION ALL SELECT 2 UNION ALL SELECT 7) u
CROSS JOIN (SELECT id AS cid FROM coupon WHERE deleted = 0 ORDER BY id LIMIT 4) c
CROSS JOIN (SELECT 'UNUSED' AS st UNION ALL SELECT 'USED') s
WHERE NOT EXISTS (
  SELECT 1 FROM user_coupon uc WHERE uc.user_id = u.uid AND uc.coupon_id = c.cid
);

-- ---------- 4.10 积分记录（points_record）：补足到 15 条 ----------
INSERT INTO points_record (user_id, type, amount, balance_after, source, order_no, remark, create_time)
SELECT o.user_id, 'EARN', 20, 100 + o.id * 20, 'ORDER', o.order_no, '演示积分：下单奖励',
       o.create_time + INTERVAL 1 MINUTE
FROM orders o
WHERE o.order_no LIKE 'DEMO-O%' AND o.pay_status = 'PAID' AND o.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM points_record pr WHERE pr.order_no = o.order_no AND pr.source = 'ORDER'
  );

INSERT INTO points_record (user_id, type, amount, balance_after, source, remark, create_time)
SELECT u.uid, 'SIGNIN', 5, 200 + u.uid, 'SIGNIN', '演示积分：每日签到', NOW() - INTERVAL u.uid DAY
FROM (SELECT 1 AS uid UNION ALL SELECT 2 UNION ALL SELECT 7) u
WHERE NOT EXISTS (
  SELECT 1 FROM points_record pr WHERE pr.user_id = u.uid AND pr.source = 'SIGNIN'
);

-- ---------- 4.11 提现（withdrawal）：补足到 20 条，覆盖各状态 ----------
INSERT INTO withdrawal
  (withdraw_no, user_id, subject_id, role_type, amount, fee, status, apply_time, review_time, pay_time, failure_reason)
VALUES
  ('DEMO-WD2026001', 1, 101, 'STORE',    50000, 500, 'APPLIED',  NOW() - INTERVAL 5 DAY, NULL, NULL, NULL),
  ('DEMO-WD2026002', 2, 102, 'STORE',    30000, 300, 'APPLIED',  NOW() - INTERVAL 4 DAY, NULL, NULL, NULL),
  ('DEMO-WD2026003', 7, 201, 'INVESTOR', 80000, 800, 'APPROVED', NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 3 DAY, NULL, NULL),
  ('DEMO-WD2026004', 1, 202, 'INVESTOR', 20000, 200, 'PAID',     NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 2 DAY, NULL),
  ('DEMO-WD2026005', 2, 301, 'CHANNEL',  60000, 600, 'PAID',     NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY, NULL),
  ('DEMO-WD2026006', 7, 302, 'CHANNEL',  45000, 450, 'REJECTED', NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY, NULL, '演示驳回：账户信息不完整'),
  ('DEMO-WD2026007', 1, 103, 'STORE',    15000, 150, 'REJECTED', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NULL, '演示驳回：金额低于起提额')
ON DUPLICATE KEY UPDATE
  status = VALUES(status), amount = VALUES(amount), fee = VALUES(fee),
  review_time = VALUES(review_time), pay_time = VALUES(pay_time),
  failure_reason = VALUES(failure_reason), deleted = 0;

-- ---------- 4.12 角色开通申请（role_application）：原为 0，补 12 条 ----------
-- 幂等说明：本表无业务唯一键，ON DUPLICATE KEY 无从生效，
-- 故统一用 INSERT ... SELECT ... WHERE NOT EXISTS（按 applicant_phone 判重），
-- 保证重复执行不会产生重复行。
INSERT INTO role_application
  (user_id, role_type, applicant_name, applicant_phone, extra_form, status, apply_time, review_time, reviewer, subject_id)
SELECT * FROM (
  SELECT 1 AS uid, 'STORE' AS rt, '演示申请人01' AS an, '13900000001' AS ap,
         JSON_OBJECT('storeName','演示门店01') AS ef, 'PENDING' AS st,
         NOW() - INTERVAL 6 DAY AS at, NULL AS rvt, NULL AS rv, NULL AS sid
  UNION ALL SELECT 2, 'STORE',    '演示申请人02', '13900000002', JSON_OBJECT('storeName','演示门店02'), 'PENDING',  NOW() - INTERVAL 5 DAY, NULL, NULL, NULL
  UNION ALL SELECT 7, 'INVESTOR', '演示申请人03', '13900000003', JSON_OBJECT('amount','50000'),         'PENDING',  NOW() - INTERVAL 5 DAY, NULL, NULL, NULL
  UNION ALL SELECT 1, 'CHANNEL',  '演示申请人04', '13900000004', JSON_OBJECT('channel','演示渠道04'),   'APPROVED', NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 3 DAY, 'admin', 301
  UNION ALL SELECT 2, 'SUPPLIER', '演示申请人05', '13900000005', JSON_OBJECT('company','演示供应商05'), 'APPROVED', NOW() - INTERVAL 4 DAY, NOW() - INTERVAL 3 DAY, 'admin', 401
  UNION ALL SELECT 7, 'STORE',    '演示申请人06', '13900000006', JSON_OBJECT('storeName','演示门店06'), 'APPROVED', NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 2 DAY, 'admin', 101
  UNION ALL SELECT 1, 'INVESTOR', '演示申请人07', '13900000007', JSON_OBJECT('amount','80000'),         'REJECTED', NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 2 DAY, 'admin', NULL
  UNION ALL SELECT 2, 'CHANNEL',  '演示申请人08', '13900000008', JSON_OBJECT('channel','演示渠道08'),   'REJECTED', NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY, 'admin', NULL
  UNION ALL SELECT 7, 'SUPPLIER', '演示申请人09', '13900000009', JSON_OBJECT('company','演示供应商09'), 'PENDING',  NOW() - INTERVAL 2 DAY, NULL, NULL, NULL
  UNION ALL SELECT 1, 'STORE',    '演示申请人10', '13900000010', JSON_OBJECT('storeName','演示门店10'), 'PENDING',  NOW() - INTERVAL 1 DAY, NULL, NULL, NULL
  UNION ALL SELECT 2, 'INVESTOR', '演示申请人11', '13900000011', JSON_OBJECT('amount','120000'),        'APPROVED', NOW() - INTERVAL 1 DAY, NOW(), 'admin', 201
  UNION ALL SELECT 7, 'CHANNEL',  '演示申请人12', '13900000012', JSON_OBJECT('channel','演示渠道12'),   'PENDING',  NOW(), NULL, NULL, NULL
) src
WHERE NOT EXISTS (
  SELECT 1 FROM role_application ra WHERE ra.applicant_phone = src.ap AND ra.deleted = 0
);

-- ---------- 4.13 对账异常池（reconcile_issue）：原为 0，补 12 条 ----------
-- 幂等：按 issue_type + order_no 判重（本表无唯一键）。
INSERT INTO reconcile_issue
  (issue_type, order_no, system_value, third_value, diff_amount, found_time, status)
SELECT * FROM (
  SELECT 'AMOUNT_MISMATCH' AS it, 'DEMO-O20260101' AS ono, '1800' AS sv, '1750' AS tv, 50   AS da, NOW() - INTERVAL 8 DAY AS ft, 'OPEN' AS st
  UNION ALL SELECT 'AMOUNT_MISMATCH', 'DEMO-O20260102', '2000', '2000', 0,    NOW() - INTERVAL 8 DAY, 'RESOLVED'
  UNION ALL SELECT 'MISSING_PAYMENT', 'DEMO-O20260103', '2100', NULL,   2100, NOW() - INTERVAL 7 DAY, 'OPEN'
  UNION ALL SELECT 'MISSING_ORDER',   'DEMO-O20260104', NULL,   '2400', 2400, NOW() - INTERVAL 7 DAY, 'OPEN'
  UNION ALL SELECT 'STATUS_MISMATCH', 'DEMO-O20260105', 'PAID', 'UNPAID', 0,  NOW() - INTERVAL 6 DAY, 'PROCESSING'
  UNION ALL SELECT 'AMOUNT_MISMATCH', 'DEMO-O20260106', '1900', '1850', 50,   NOW() - INTERVAL 6 DAY, 'RESOLVED'
  UNION ALL SELECT 'DUPLICATE_PAY',   'DEMO-O20260107', '2200', '4400', 2200, NOW() - INTERVAL 5 DAY, 'OPEN'
  UNION ALL SELECT 'STATUS_MISMATCH', 'DEMO-O20260108', 'REFUNDED', 'PAID', 0, NOW() - INTERVAL 5 DAY, 'OPEN'
  UNION ALL SELECT 'AMOUNT_MISMATCH', 'DEMO-O20260109', '2500', '2450', 50,   NOW() - INTERVAL 4 DAY, 'PROCESSING'
  UNION ALL SELECT 'MISSING_PAYMENT', 'DEMO-O20260110', '1700', NULL,   1700, NOW() - INTERVAL 4 DAY, 'OPEN'
  UNION ALL SELECT 'DUPLICATE_PAY',   'DEMO-O20260111', '1600', '3200', 1600, NOW() - INTERVAL 3 DAY, 'RESOLVED'
  UNION ALL SELECT 'AMOUNT_MISMATCH', 'DEMO-O20260112', '2300', '2250', 50,   NOW() - INTERVAL 3 DAY, 'OPEN'
) src
WHERE NOT EXISTS (
  SELECT 1 FROM reconcile_issue ri WHERE ri.issue_type = src.it AND ri.order_no = src.ono AND ri.deleted = 0
);

-- ---------- 4.14 资金池（fund_pool）：原为 0，补 6 条 ----------
-- 幂等：按 pool_name 判重（本表无唯一键）。
INSERT INTO fund_pool (pool_name, total_balance)
SELECT * FROM (
  SELECT '【演示】平台资金池' AS pn,       128000000 AS tb
  UNION ALL SELECT '【演示】门店结算资金池',    86000000
  UNION ALL SELECT '【演示】投资人分账资金池',  42000000
  UNION ALL SELECT '【演示】渠道推广资金池',    18000000
  UNION ALL SELECT '【演示】供应商结算资金池',  26000000
  UNION ALL SELECT '【演示】风险准备金',        9000000
) src
WHERE NOT EXISTS (
  SELECT 1 FROM fund_pool fp WHERE fp.pool_name = src.pn AND fp.deleted = 0
);

-- ---------- 4.15 分享有礼记录（referral_record）：原为 0，补 15 条 ----------
-- invitee_user_id 有唯一键，ON DUPLICATE KEY 可直接兜底。
INSERT INTO referral_record (inviter_user_id, invitee_user_id, status, first_order_status, create_time)
VALUES
  (1, 1001, 'COMPLETED', 'PAID', NOW() - INTERVAL 14 DAY),
  (1, 1002, 'COMPLETED', 'PAID', NOW() - INTERVAL 13 DAY),
  (2, 1003, 'COMPLETED', 'PAID', NOW() - INTERVAL 12 DAY),
  (2, 1004, 'PENDING',   'UNPAID', NOW() - INTERVAL 12 DAY),
  (7, 1005, 'COMPLETED', 'PAID', NOW() - INTERVAL 11 DAY),
  (7, 1006, 'PENDING',   'UNPAID', NOW() - INTERVAL 10 DAY),
  (1, 1007, 'EXPIRED',   'UNPAID', NOW() - INTERVAL 9 DAY),
  (2, 1008, 'COMPLETED', 'PAID', NOW() - INTERVAL 8 DAY),
  (7, 1009, 'COMPLETED', 'PAID', NOW() - INTERVAL 7 DAY),
  (1, 1010, 'PENDING',   'UNPAID', NOW() - INTERVAL 6 DAY),
  (2, 1011, 'EXPIRED',   'UNPAID', NOW() - INTERVAL 5 DAY),
  (7, 1012, 'COMPLETED', 'PAID', NOW() - INTERVAL 4 DAY),
  (1, 1013, 'COMPLETED', 'PAID', NOW() - INTERVAL 3 DAY),
  (2, 1014, 'PENDING',   'UNPAID', NOW() - INTERVAL 2 DAY),
  (7, 1015, 'COMPLETED', 'PAID', NOW() - INTERVAL 1 DAY)
ON DUPLICATE KEY UPDATE
  status = VALUES(status), first_order_status = VALUES(first_order_status);

-- =============================================================
-- 5. 清理 SQL（按需手工执行，不在迁移中自动运行）
--
-- 全部演示数据均可通过下列语句移除，不影响真实业务数据：
--
--   DELETE FROM referral_record   WHERE invitee_user_id BETWEEN 1001 AND 1015;
--   DELETE FROM fund_pool         WHERE pool_name LIKE '【演示】%';
--   DELETE FROM reconcile_issue   WHERE order_no LIKE 'DEMO-O%';
--   DELETE FROM role_application  WHERE applicant_name LIKE '演示申请人%';
--   DELETE FROM withdrawal        WHERE withdraw_no LIKE 'DEMO-WD%';
--   DELETE FROM points_record     WHERE remark LIKE '演示积分%';
--   DELETE FROM user_coupon       WHERE id > 0 AND user_id IN (1,2,7);  -- 需按实际新增范围确认
--   DELETE FROM member_level      WHERE level_code LIKE 'DEMO-L%';
--   DELETE FROM comments          WHERE content LIKE '【演示评论】%';
--   DELETE FROM verify_record     WHERE verify_code LIKE 'DEMO-VC%';
--   DELETE FROM refund            WHERE refund_no LIKE 'DEMO-RF%';
--   DELETE FROM payment           WHERE payment_no LIKE 'DEMO-PAY%';
--   DELETE FROM orders            WHERE order_no LIKE 'DEMO-O%';
--   DELETE FROM product_store     WHERE product_id IN (SELECT id FROM product WHERE product_id LIKE 'DEMO-P%');
--   DELETE FROM product_spec      WHERE product_id IN (SELECT id FROM product WHERE product_id LIKE 'DEMO-P%');
--   DELETE FROM product           WHERE product_id LIKE 'DEMO-P%';
--
-- 注意：product 新增的 cost_price / platform_commission 两列为结构性变更，
--      如需回滚请单独执行 ALTER TABLE product DROP COLUMN ...。
-- =============================================================
