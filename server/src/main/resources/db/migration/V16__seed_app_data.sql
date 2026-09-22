-- =============================================================
-- 小程序端业务数据落库（假数据清理 · 阶段 C）
-- 由 server/scripts/gen-app-data-seed.cjs 自动生成，请勿手工编辑。
--
-- 背景：以下数据原先只存在于 user-h5/data/mock.js，前端直接 require 读取。
-- 本迁移把它们补入数据库，前端改为走 /api/v1/app/** 接口读取。
-- 原则：只补 DB 现有 seed 缺失的字段与内容，不覆盖运营已配置数据。
--
-- =============================================================

-- ---------- 1. product 详情扩展字段（原 mock specDetail 内字段） ----------
ALTER TABLE product
    ADD COLUMN gallery_image    VARCHAR(255) NULL COMMENT "详情主图" AFTER image,
    ADD COLUMN image_disclaimer VARCHAR(255) NULL COMMENT "图片免责声明" AFTER gallery_image,
    ADD COLUMN promotion_text   VARCHAR(255) NULL COMMENT "促销文案" AFTER image_disclaimer,
    ADD COLUMN price_label      VARCHAR(32)  NULL COMMENT "价格标签" AFTER promotion_text,
    ADD COLUMN discount_rate    INT          NULL COMMENT "折扣万分比，10000=无折扣" AFTER price_label,
    ADD COLUMN spec_tag         VARCHAR(64)  NULL COMMENT "详情页标签文案" AFTER discount_rate,
    ADD COLUMN badge_icon       VARCHAR(255) NULL COMMENT "商品角标图标" AFTER spec_tag;

UPDATE product SET
  gallery_image = CASE product_id
    WHEN 'classic-001' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'classic-002' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'classic-003' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'classic-004' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'classic-005' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'herbal-001' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'herbal-002' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'herbal-003' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'leaf-001' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'leaf-002' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'leaf-003' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'traditional-001' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'traditional-002' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'featured-001' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'featured-002' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'featured-003' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'season-001' THEN '/assets/images/3x/menu-product.jpg'
    WHEN 'season-002' THEN '/assets/images/3x/menu-product.jpg'
    ELSE gallery_image END,
  image_disclaimer = CASE product_id
    WHEN 'classic-001' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'classic-002' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'classic-003' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'classic-004' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'classic-005' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'herbal-001' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'herbal-002' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'herbal-003' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'leaf-001' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'leaf-002' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'leaf-003' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'traditional-001' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'traditional-002' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'featured-001' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'featured-002' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'featured-003' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'season-001' THEN '图片与杯型仅供参考，具体请以实物为准'
    WHEN 'season-002' THEN '图片与杯型仅供参考，具体请以实物为准'
    ELSE image_disclaimer END,
  promotion_text = CASE product_id
    WHEN 'classic-001' THEN '周三会员日招牌饮品85折'
    WHEN 'classic-002' THEN '周三会员日招牌饮品85折'
    WHEN 'classic-003' THEN '周三会员日招牌饮品85折'
    WHEN 'classic-004' THEN '周三会员日招牌饮品85折'
    WHEN 'classic-005' THEN '周三会员日招牌饮品85折'
    WHEN 'herbal-001' THEN '周三会员日招牌饮品85折'
    WHEN 'herbal-002' THEN '周三会员日招牌饮品85折'
    WHEN 'herbal-003' THEN '周三会员日招牌饮品85折'
    WHEN 'leaf-001' THEN '周三会员日招牌饮品85折'
    WHEN 'leaf-002' THEN '周三会员日招牌饮品85折'
    WHEN 'leaf-003' THEN '周三会员日招牌饮品85折'
    WHEN 'traditional-001' THEN '周三会员日招牌饮品85折'
    WHEN 'traditional-002' THEN '周三会员日招牌饮品85折'
    WHEN 'featured-001' THEN '周三会员日招牌饮品85折'
    WHEN 'featured-002' THEN '周三会员日招牌饮品85折'
    WHEN 'featured-003' THEN '周三会员日招牌饮品85折'
    WHEN 'season-001' THEN '周三会员日招牌饮品85折'
    WHEN 'season-002' THEN '周三会员日招牌饮品85折'
    ELSE promotion_text END,
  price_label = CASE product_id
    WHEN 'classic-001' THEN '小程序价'
    WHEN 'classic-002' THEN '小程序价'
    WHEN 'classic-003' THEN '小程序价'
    WHEN 'classic-004' THEN '小程序价'
    WHEN 'classic-005' THEN '小程序价'
    WHEN 'herbal-001' THEN '小程序价'
    WHEN 'herbal-002' THEN '小程序价'
    WHEN 'herbal-003' THEN '小程序价'
    WHEN 'leaf-001' THEN '小程序价'
    WHEN 'leaf-002' THEN '小程序价'
    WHEN 'leaf-003' THEN '小程序价'
    WHEN 'traditional-001' THEN '小程序价'
    WHEN 'traditional-002' THEN '小程序价'
    WHEN 'featured-001' THEN '小程序价'
    WHEN 'featured-002' THEN '小程序价'
    WHEN 'featured-003' THEN '小程序价'
    WHEN 'season-001' THEN '小程序价'
    WHEN 'season-002' THEN '小程序价'
    ELSE price_label END,
  discount_rate = CASE product_id
    WHEN 'classic-001' THEN 8500
    WHEN 'classic-002' THEN 8500
    WHEN 'classic-003' THEN 8500
    WHEN 'classic-004' THEN 8500
    WHEN 'classic-005' THEN 10000
    WHEN 'herbal-001' THEN 8500
    WHEN 'herbal-002' THEN 8500
    WHEN 'herbal-003' THEN 8500
    WHEN 'leaf-001' THEN 8500
    WHEN 'leaf-002' THEN 8500
    WHEN 'leaf-003' THEN 8500
    WHEN 'traditional-001' THEN 8500
    WHEN 'traditional-002' THEN 8500
    WHEN 'featured-001' THEN 8500
    WHEN 'featured-002' THEN 8500
    WHEN 'featured-003' THEN 8500
    WHEN 'season-001' THEN 8500
    WHEN 'season-002' THEN 8500
    ELSE discount_rate END,
  spec_tag = CASE product_id
    WHEN 'classic-001' THEN '五窨茉莉花茶'
    WHEN 'classic-002' THEN '五窨茉莉花茶'
    WHEN 'classic-003' THEN '五窨茉莉花茶'
    WHEN 'classic-004' THEN '五窨茉莉花茶'
    WHEN 'classic-005' THEN '红苹果乌龙'
    WHEN 'herbal-001' THEN '五窨茉莉花茶'
    WHEN 'herbal-002' THEN '五窨茉莉花茶'
    WHEN 'herbal-003' THEN '五窨茉莉花茶'
    WHEN 'leaf-001' THEN '五窨茉莉花茶'
    WHEN 'leaf-002' THEN '五窨茉莉花茶'
    WHEN 'leaf-003' THEN '五窨茉莉花茶'
    WHEN 'traditional-001' THEN '五窨茉莉花茶'
    WHEN 'traditional-002' THEN '五窨茉莉花茶'
    WHEN 'featured-001' THEN '五窨茉莉花茶'
    WHEN 'featured-002' THEN '五窨茉莉花茶'
    WHEN 'featured-003' THEN '五窨茉莉花茶'
    WHEN 'season-001' THEN '五窨茉莉花茶'
    WHEN 'season-002' THEN '五窨茉莉花茶'
    ELSE spec_tag END,
  badge_icon = CASE product_id
    WHEN 'classic-001' THEN '/assets/icons/lucide/member-gold.svg'
    WHEN 'classic-002' THEN ''
    WHEN 'classic-003' THEN ''
    WHEN 'classic-004' THEN ''
    WHEN 'classic-005' THEN ''
    WHEN 'herbal-001' THEN ''
    WHEN 'herbal-002' THEN ''
    WHEN 'herbal-003' THEN '/assets/icons/lucide/member-gold.svg'
    WHEN 'leaf-001' THEN ''
    WHEN 'leaf-002' THEN ''
    WHEN 'leaf-003' THEN ''
    WHEN 'traditional-001' THEN ''
    WHEN 'traditional-002' THEN ''
    WHEN 'featured-001' THEN '/assets/icons/lucide/member-gold.svg'
    WHEN 'featured-002' THEN ''
    WHEN 'featured-003' THEN ''
    WHEN 'season-001' THEN ''
    WHEN 'season-002' THEN ''
    ELSE badge_icon END
;

-- ---------- 2. store_profile 门店业务编码（前端以 code 作为门店 id） ----------
ALTER TABLE store_profile ADD COLUMN code VARCHAR(64) NULL COMMENT "门店业务编码" AFTER subject_id;

UPDATE store_profile SET code = CASE subject_id
    WHEN 101 THEN 'store-001'
    WHEN 102 THEN 'store-002'
    WHEN 103 THEN 'store-003'
    WHEN 104 THEN 'store-004'
    WHEN 105 THEN 'store-005'
    ELSE code END;

-- ---------- 3. points_product 分类与券展示字段 ----------
ALTER TABLE points_product
    ADD COLUMN category         VARCHAR(32)  NULL COMMENT "分类 all/pet/coupon" AFTER description,
    ADD COLUMN purchase_limit   INT          NOT NULL DEFAULT 0 COMMENT "每人限购 0=不限" AFTER category,
    ADD COLUMN display_type     VARCHAR(32)  NULL COMMENT "券类型 fixed/buyone/halfprice" AFTER purchase_limit,
    ADD COLUMN coupon_amount    BIGINT       NOT NULL DEFAULT 0 COMMENT "券面额(分)" AFTER display_type,
    ADD COLUMN coupon_condition VARCHAR(64)  NULL COMMENT "券门槛文案" AFTER coupon_amount,
    ADD COLUMN badge_in_image   TINYINT      NOT NULL DEFAULT 0 COMMENT "角标叠加在图片上" AFTER badge;

-- V6 已初始化 2 行（points-pet-food / points-coupon-3），这里补齐分类与券字段。
UPDATE points_product SET
  category = CASE code
    WHEN 'points-pet-food' THEN 'pet'
    WHEN 'points-matcha-buy-one' THEN 'coupon'
    WHEN 'points-single-cup' THEN 'coupon'
    WHEN 'points-second-cup-half' THEN 'coupon'
    ELSE category END,
  purchase_limit = CASE code
    WHEN 'points-pet-food' THEN 0
    WHEN 'points-matcha-buy-one' THEN 1
    WHEN 'points-single-cup' THEN 1
    WHEN 'points-second-cup-half' THEN 1
    ELSE purchase_limit END,
  display_type = CASE code
    WHEN 'points-pet-food' THEN 'fixed'
    WHEN 'points-matcha-buy-one' THEN 'buyone'
    WHEN 'points-single-cup' THEN 'fixed'
    WHEN 'points-second-cup-half' THEN 'halfprice'
    ELSE display_type END,
  coupon_amount = CASE code
    WHEN 'points-pet-food' THEN 0
    WHEN 'points-matcha-buy-one' THEN 0
    WHEN 'points-single-cup' THEN 300
    WHEN 'points-second-cup-half' THEN 0
    ELSE coupon_amount END,
  coupon_condition = CASE code
    WHEN 'points-pet-food' THEN '不限'
    WHEN 'points-matcha-buy-one' THEN '不限'
    WHEN 'points-single-cup' THEN '不限'
    WHEN 'points-second-cup-half' THEN '不限'
    ELSE coupon_condition END,
  badge_in_image = CASE code
    WHEN 'points-pet-food' THEN 0
    WHEN 'points-matcha-buy-one' THEN 0
    WHEN 'points-single-cup' THEN 1
    WHEN 'points-second-cup-half' THEN 1
    ELSE badge_in_image END
;

-- 补充小程序展出的兑换商品（与设计稿 4 张商品卡一致）
INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES
(100, 'points-matcha-buy-one', '超浓抹茶系列买一送一券', '/assets/images/3x/points-product-matcha.jpg', 300, 51, '限时抢兑', '*每人仅可兑换一次', '超浓抹茶系列买一送一券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。', 'enabled', 'coupon', 1, 'buyone', 0, '不限', 0),
(101, 'points-single-cup', '超浓抹茶系列单杯3元券', '/assets/images/3x/points-product-single.jpg', 300, 51, '限时抢兑', '*每人仅可兑换一次', '超浓抹茶系列单杯3元优惠券，兑换后可在指定饮品结算时抵扣，具体适用范围和有效期以券面说明为准。', 'enabled', 'coupon', 1, 'fixed', 300, '不限', 1),
(102, 'points-second-cup-half', '超浓抹茶系列第2杯半价券', '/assets/images/3x/points-product-half.jpg', 300, 51, '限时抢兑', '*每人仅可兑换一次', '超浓抹茶系列第2杯半价券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。', 'enabled', 'coupon', 1, 'halfprice', 0, '不限', 1)
ON DUPLICATE KEY UPDATE name=VALUES(name), image=VALUES(image), points=VALUES(points), stock=VALUES(stock),
  badge=VALUES(badge), limit_text=VALUES(limit_text), description=VALUES(description), category=VALUES(category),
  purchase_limit=VALUES(purchase_limit), display_type=VALUES(display_type), coupon_amount=VALUES(coupon_amount),
  coupon_condition=VALUES(coupon_condition), badge_in_image=VALUES(badge_in_image);

-- ---------- 4. gift_card_denomination 分组与展示信息（礼品卡页分组展示依赖） ----------
ALTER TABLE gift_card_denomination
    ADD COLUMN group_id    VARCHAR(32)  NULL COMMENT "分组 id" AFTER code,
    ADD COLUMN group_title VARCHAR(64)  NULL COMMENT "分组标题" AFTER group_id,
    ADD COLUMN card_name   VARCHAR(64)  NULL COMMENT "卡面名称" AFTER group_title,
    ADD COLUMN card_image  VARCHAR(255) NULL COMMENT "卡面图片" AFTER card_name,
    ADD COLUMN sale_price  BIGINT       NOT NULL DEFAULT 0 COMMENT "售价(分)" AFTER amount,
    ADD COLUMN sort        INT          NOT NULL DEFAULT 0 COMMENT "排序" AFTER sale_price;

-- 原 3 条按面额的占位数据改为「分组 x 面额」的完整卡面清单，先移除旧占位行。
DELETE FROM gift_card_denomination WHERE code IN ('gift-value-100', 'gift-value-200', 'gift-value-500');

INSERT INTO gift_card_denomination (code, group_id, group_title, card_name, card_image, name, amount, sale_price, status, sort) VALUES
('gift-001-100', 'popular', '人气礼品卡', '超浓抹茶', '/assets/images/3x/gift-card-matcha.jpg', '超浓抹茶 100元礼品卡', 10000, 10000, 'enabled', 0),
('gift-001-200', 'popular', '人气礼品卡', '超浓抹茶', '/assets/images/3x/gift-card-matcha.jpg', '超浓抹茶 200元礼品卡', 20000, 20000, 'enabled', 1),
('gift-001-500', 'popular', '人气礼品卡', '超浓抹茶', '/assets/images/3x/gift-card-matcha.jpg', '超浓抹茶 500元礼品卡', 50000, 50000, 'enabled', 2),
('gift-002-100', 'popular', '人气礼品卡', '相遇很美好', '/assets/images/3x/gift-card-jasmine.jpg', '相遇很美好 100元礼品卡', 10000, 10000, 'enabled', 3),
('gift-002-200', 'popular', '人气礼品卡', '相遇很美好', '/assets/images/3x/gift-card-jasmine.jpg', '相遇很美好 200元礼品卡', 20000, 20000, 'enabled', 4),
('gift-002-500', 'popular', '人气礼品卡', '相遇很美好', '/assets/images/3x/gift-card-jasmine.jpg', '相遇很美好 500元礼品卡', 50000, 50000, 'enabled', 5),
('gift-003-100', 'limited', '限定心意卡', '限定心意', '/assets/images/3x/gift-card-limited.jpg', '限定心意 100元礼品卡', 10000, 10000, 'enabled', 6),
('gift-003-200', 'limited', '限定心意卡', '限定心意', '/assets/images/3x/gift-card-limited.jpg', '限定心意 200元礼品卡', 20000, 20000, 'enabled', 7),
('gift-003-500', 'limited', '限定心意卡', '限定心意', '/assets/images/3x/gift-card-limited.jpg', '限定心意 500元礼品卡', 50000, 50000, 'enabled', 8)
ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), group_title=VALUES(group_title), card_name=VALUES(card_name),
  card_image=VALUES(card_image), name=VALUES(name), amount=VALUES(amount), sale_price=VALUES(sale_price), sort=VALUES(sort);

-- ---------- 5. app_config 补齐小程序运营配置 ----------
-- home_shortcuts / menu_activity / profile_functions / signin_rules / signin_rewards / app_cities
-- 已由 V10 初始化，这里仅补签到日历基准数据。

INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES
('points_signin', '签到日历与奖励', '{"year":2026,"month":9,"today":"2026-09-17","todayLabel":"9.17","weekDates":[{"key":"2026-09-16","label":"9.16"},{"key":"2026-09-17","label":"9.17"},{"key":"2026-09-18","label":"9.18"},{"key":"2026-09-19","label":"9.19"},{"key":"2026-09-20","label":"9.20"},{"key":"2026-09-21","label":"9.21"}]}', 7, '签到页日历基准数据')
ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);

