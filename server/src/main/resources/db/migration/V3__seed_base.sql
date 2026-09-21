-- =============================================================
-- 五零时光 基线数据
-- =============================================================

-- 后台账号 admin / Admin@123
INSERT INTO sys_user (id, username, password, nick_name, status) VALUES
(1, 'admin', '$2a$10$tW4gWMzHu/ulbz5eKI7kiuZzZoPtxMvJNyFGsePuRY1dlL0lcyHgC', '超级管理员', 1);

INSERT INTO sys_role (id, code, name, data_scope, status) VALUES
(1, 'R_SUPER', '超级管理员', 'all', 1),
(2, 'R_ADMIN', '运营管理员', 'all', 1);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 门店类型字典
INSERT INTO sys_dict_type (id, dict_type, dict_name, status) VALUES (1, 'store_type', '门店类型', 1);
INSERT INTO sys_dict_item (dict_type_id, dict_type, item_code, item_name, sort, enabled) VALUES
(1, 'store_type', 'convenience', '便利店', 1, 1),
(1, 'store_type', 'restaurant', '餐饮', 2, 1),
(1, 'store_type', 'gym', '健身房', 3, 1),
(1, 'store_type', 'milk-tea', '奶茶/饮品', 4, 1);

-- 省市区基线
INSERT INTO region (id, parent_id, code, name, level, sort) VALUES
(1, 0, '43', '湖南省', 1, 1),
(2, 0, '44', '广东省', 1, 2),
(3, 1, '4301', '长沙市', 2, 1),
(4, 2, '4401', '广州市', 2, 1),
(5, 2, '4403', '深圳市', 2, 2);

-- 经营主体
INSERT INTO biz_subject (id, code, name, subject_type, status) VALUES
(1,   'PT-1000', '五零时光运营平台', 'PLATFORM', 'active'),
(101, 'ST-1001', '星沙乐运魔方店', 'STORE', 'active'),
(102, 'ST-1002', '松雅湖吾悦广场店', 'STORE', 'active'),
(103, 'ST-1003', '长沙高铁南站店', 'STORE', 'active'),
(104, 'ST-1004', '广州天河城店', 'STORE', 'active'),
(105, 'ST-1005', '广州北京路店', 'STORE', 'active'),
(201, 'IV-1000', '投资人甲', 'INVESTOR', 'signed'),
(202, 'IV-1001', '投资人乙', 'INVESTOR', 'signed'),
(203, 'IV-1002', '投资人丙', 'INVESTOR', 'signed'),
(301, 'RS-1000', '资源方甲', 'CHANNEL', 'active'),
(302, 'RS-1001', '资源方乙', 'CHANNEL', 'active'),
(303, 'RS-1002', '资源方丙', 'CHANNEL', 'active'),
(401, 'SU-1000', '供应商一', 'SUPPLIER', 'active'),
(402, 'SU-1001', '供应商二', 'SUPPLIER', 'active'),
(403, 'SU-1002', '供应商三', 'SUPPLIER', 'active');

INSERT INTO platform_profile (subject_id, app_id, app_secret) VALUES
(1, 'wx1234567890abcdef', '9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c');

INSERT INTO store_profile (subject_id, city, address, phone, latitude, longitude, store_type, business_status, manager, investor_subject_id, business_hours, modes, promotion, queue_count) VALUES
(101, '长沙市', '湖南省长沙市长沙县星沙街道开元东路288号乐运魔方1层L108号铺（靠近中庭）', '0731-88880001', 28.243500, 113.077600, '奶茶/饮品', 'open', '店长1', 201, '10:00-22:00', '["pickup","dinein"]', '新中式养生茶系列上新', 3),
(102, '长沙市', '湖南省长沙市长沙县东四路与滨湖东路交汇处吾悦广场1层B区B108号铺（靠近1号门）', '0731-88880002', 28.239600, 113.080300, '奶茶/饮品', 'open', '店长2', 202, '10:00-21:30', '["pickup","dinein"]', '新中式养生茶系列上新', 6),
(103, '长沙市', '湖南省长沙市雨花区花侯路长沙南站西广场1层S102号铺（地铁2号线出口旁）', '0731-88880003', 28.154000, 113.062000, '奶茶/饮品', 'open', '店长3', NULL, '07:30-22:00', '["pickup"]', '新中式养生茶系列上新', 12),
(104, '广州市', '广东省广州市天河区天河路208号天河城购物中心B1层B108号铺', '020-88880004', 23.132300, 113.327000, '奶茶/饮品', 'open', '店长4', 201, '10:00-22:00', '["pickup","dinein"]', '新中式养生茶系列上新', 0),
(105, '广州市', '广东省广州市越秀区北京路步行街238号1层102号铺（北京路地铁站B口）', '020-88880005', 23.125600, 113.269700, '奶茶/饮品', 'open', '店长5', 202, '09:30-22:30', '["pickup","dinein"]', '新中式养生茶系列上新', 0);

INSERT INTO investor_profile (subject_id, investable_store_count, sign_status) VALUES
(201, 3, 'signed'), (202, 2, 'signed'), (203, 4, 'signed');

INSERT INTO channel_profile (subject_id, location, store_type) VALUES
(301, '长沙市', '奶茶/饮品'), (302, '武汉市', '便利店'), (303, '广州市', '餐饮');

INSERT INTO supplier_profile (subject_id, product_count, status) VALUES
(401, 5, 'active'), (402, 7, 'active'), (403, 9, 'active');

-- 业务角色字典
INSERT INTO biz_role (id, code, name, description) VALUES
(1, 'consumer', '消费者', '小程序下单用户'),
(2, 'store', '门店', '核销订单、查看收益与提现'),
(3, 'channel', '渠道/资源方', '绑定门店、按门店订单提成'),
(4, 'investor', '投资人', '点位投资、月度分佣'),
(5, 'supplier', '供应商', '商品映射、销售明细'),
(6, 'platform', '平台', '平台运营');

-- 会员等级
INSERT INTO member_level (level_code, name, amount_target, discount, benefits, sort) VALUES
('Lv1', '时光卡', 0, '8折', '[{"icon":"badge-percent","count":"","text":"基础折扣"},{"icon":"star-brand","count":"","text":"生日月双倍时光币"},{"icon":"badge-japanese-yen-brand","count":"","text":"专属会员价"}]', 1),
('Lv2', '星享卡', 30000, '7折', '[{"icon":"badge-percent","count":"","text":"基础折扣"},{"icon":"star-brand","count":"","text":"生日月双倍时光币"},{"icon":"badge-japanese-yen-brand","count":"","text":"专属会员价"},{"icon":"ticket","count":"3","text":"专属优惠券"},{"icon":"gift-brand","count":"","text":"新品优先体验"}]', 2),
('Lv3', '挚友卡', 200000, '6折', '[{"icon":"badge-percent","count":"","text":"基础折扣"},{"icon":"star-brand","count":"","text":"生日月双倍时光币"},{"icon":"badge-japanese-yen-brand","count":"","text":"专属会员价"},{"icon":"ticket","count":"7","text":"专属优惠券"},{"icon":"gift-brand","count":"","text":"新品优先体验"},{"icon":"trending-up-brand","count":"","text":"时光币1.5倍"},{"icon":"gift-brand","count":"2","text":"生日免费饮品"}]', 3);

-- 积分获取规则
INSERT INTO points_earning_rule (code, action, reward, note, sort) VALUES
('consume', '每消费1元', '+1币', '基础获取通道', 1),
('signin', '每日签到', '+1币', '连续签到7天额外+20币', 2),
('invite', '邀请好友注册', '+3币/人', '好友完成首单后到账', 3),
('share', '分享订单到朋友圈/小红书', '+3币/次', '每日上限2次', 4),
('birthday', '生日当天消费', '双倍时光币', '自动触发，无需操作', 5),
('member-day', '每周四“时光日”', '全场3倍时光币', '固定会员活动日', 6);

-- 功能开关
INSERT INTO feature_flag (code, name, default_status, current_status, open_condition) VALUES
('ENABLE_COUPON', '优惠券', '开启', '开启', '优惠券活动、锁券、核销和优惠承担逻辑完成'),
('ENABLE_STORED_VALUE', '储值充值', '开启', '开启', '充值支付、余额账户、消费核销和预收台账完成'),
('ENABLE_WITHDRAWAL', '提现', '关闭', '关闭', '提现申请、审核、出款回调和失败解冻完成'),
('ENABLE_REVIEW', '评论', '开启', '开启', '评论提交、审核和订单评价状态完成');

-- 全局默认分账规则（万分比，合计 10000）
INSERT INTO split_rule (code, name, scope, platform_ratio, store_ratio, channel_ratio, investor_ratio, supplier_ratio, status) VALUES
('SR-1000', '全局默认分账', 'GLOBAL', 1000, 5000, 1500, 1500, 1000, 'enabled');
