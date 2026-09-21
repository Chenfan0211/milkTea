-- =============================================================
-- 营销 + 角色关系 种子数据（P1/P2）
-- =============================================================

-- 示例用户（会话期演示账号）
INSERT INTO app_user (id, open_id, nick_name, phone, vip_level, points, balance, business_role, bound_subject_id, status) VALUES
(1, 'oDemoUser0001', '微信用户', '13612345792', 'Lv1', 10000, 0, NULL, NULL, 1),
(2, 'oDemoUser0002', '测试用户', '13612345793', 'Lv1', 0, 0, NULL, NULL, 1);

-- 渠道绑定门店（渠道永久归因）
INSERT INTO channel_store (channel_subject_id, store_subject_id) VALUES
(301, 101),
(301, 102),
(302, 103),
(303, 104);

-- 优惠券模板（金额单位：分）
INSERT INTO coupon (id, code, name, type, amount, threshold, brand, scenes, source, description, image, validity_type, validity_days, usage_time, stock, status) VALUES
(1, 'coupon-001', '五零时光3元代金券（满20）', 'voucher', 300, 2000, '五零时光', '买单、堂食(门店就餐)、堂食(打包外带)', '开卡权益',
 '五零时光送您一张任意饮品3元券（满20可用，仅饮品可用），可在五零时光全门店使用。', '/assets/images/3x/menu-product.jpg', 'DAYS', 15, '00:00:00~23:59:59', 1000, 'enabled'),
(2, 'coupon-002', '五零时光5元代金券（满30）', 'voucher', 500, 3000, '五零时光', '买单、堂食(门店就餐)', '储值赠送',
 '五零时光送您一张任意饮品5元券（满30可用）。', '/assets/images/3x/menu-product.jpg', 'DAYS', 30, '00:00:00~23:59:59', 500, 'enabled'),
(3, 'coupon-003', '新人2元代金券（满15）', 'voucher', 200, 1500, '五零时光', '买单', '新人礼包',
 '新用户专享2元券（满15可用）。', '/assets/images/3x/menu-product.jpg', 'DAYS', 7, '00:00:00~23:59:59', 2000, 'enabled');

-- 储值套餐（金额：分）
INSERT INTO stored_value_package (id, code, name, amount, status) VALUES
(1, 'stored-value-100', '充100送优惠券', 10000, 'enabled'),
(2, 'stored-value-200', '充200送优惠券', 20000, 'enabled'),
(3, 'stored-value-500', '充500送优惠券', 50000, 'enabled');

INSERT INTO stored_value_package_coupon (package_id, coupon_id, count) VALUES
(1, 2, 2), (1, 3, 2),
(2, 2, 4), (2, 3, 4),
(3, 2, 10), (3, 3, 10);

-- 礼品卡面额（金额：分）
INSERT INTO gift_card_denomination (id, code, name, amount, status) VALUES
(1, 'gift-value-100', '100元礼品卡', 10000, 'enabled'),
(2, 'gift-value-200', '200元礼品卡', 20000, 'enabled'),
(3, 'gift-value-500', '500元礼品卡', 50000, 'enabled');

-- 积分商品
INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status) VALUES
(1, 'points-pet-food', '五零时光公益宠粮', '/assets/images/3x/points-product-pet.jpg', 20, 5879, '限时抢兑', '', '【五零时光公益宠粮】每月善款用于购买大米捐赠并持续追踪后续。', 'enabled'),
(2, 'points-coupon-3', '3元代金券', '/assets/images/3x/menu-product.jpg', 300, 1000, '热兑', '', '使用时光币兑换3元代金券（满20可用）。', 'enabled');

-- 签到规则
INSERT INTO points_signin_rule (id, daily, streak_days, streak_reward) VALUES (1, 1, 7, 20);
