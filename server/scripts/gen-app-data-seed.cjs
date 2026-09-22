// 生成 V16 seed：把 user-h5/data/mock.js 中仍为「本地业务假数据」的部分落库。
// 用法：node server/scripts/gen-app-data-seed.cjs
const fs = require('fs');
const path = require('path');
const catalog = require('./data/menu-catalog.cjs');

// 积分商品 / 礼品卡等「展示元数据」原先只存在于 user-h5/data/mock.js。
// 阶段 C 后 mock.js 已清空业务数据，这里改为内置常量，生成结果落到 V16。
const POINTS_PRODUCTS = [
  { id: 'points-pet-food', category: 'pet', purchaseLimit: 0, displayType: 'fixed', amount: 0, condition: '不限', badgeInImage: false },
  { id: 'points-matcha-buy-one', category: 'coupon', purchaseLimit: 1, displayType: 'buyone', amount: 0, condition: '不限', badgeInImage: false },
  { id: 'points-single-cup', category: 'coupon', purchaseLimit: 1, displayType: 'fixed', amount: 3, condition: '不限', badgeInImage: true },
  { id: 'points-second-cup-half', category: 'coupon', purchaseLimit: 1, displayType: 'halfprice', amount: 0, condition: '不限', badgeInImage: true }
];

// 需要补入的积分商品（V6 已含 points-pet-food 与 points-coupon-3）
const EXTRA_POINTS_PRODUCTS = [
  [100, "'points-matcha-buy-one'", "'超浓抹茶系列买一送一券'", "'/assets/images/3x/points-product-matcha.jpg'", 300, 51, "'限时抢兑'", "'*每人仅可兑换一次'",
    "'超浓抹茶系列买一送一券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。'", "'enabled'", "'coupon'", 1, "'buyone'", 0, "'不限'", 0],
  [101, "'points-single-cup'", "'超浓抹茶系列单杯3元券'", "'/assets/images/3x/points-product-single.jpg'", 300, 51, "'限时抢兑'", "'*每人仅可兑换一次'",
    "'超浓抹茶系列单杯3元优惠券，兑换后可在指定饮品结算时抵扣，具体适用范围和有效期以券面说明为准。'", "'enabled'", "'coupon'", 1, "'fixed'", 300, "'不限'", 1],
  [102, "'points-second-cup-half'", "'超浓抹茶系列第2杯半价券'", "'/assets/images/3x/points-product-half.jpg'", 300, 51, "'限时抢兑'", "'*每人仅可兑换一次'",
    "'超浓抹茶系列第2杯半价券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。'", "'enabled'", "'coupon'", 1, "'halfprice'", 0, "'不限'", 1]
];

const GIFT_CARD_GROUPS = [
  { id: 'popular', title: '人气礼品卡', cards: [
    { id: 'gift-001', name: '超浓抹茶', image: '/assets/images/3x/gift-card-matcha.jpg' },
    { id: 'gift-002', name: '相遇很美好', image: '/assets/images/3x/gift-card-jasmine.jpg' }
  ] },
  { id: 'limited', title: '限定心意卡', cards: [
    { id: 'gift-003', name: '限定心意', image: '/assets/images/3x/gift-card-limited.jpg' }
  ] }
];

const POINTS_SIGNIN_WEEK_DATES = [
  { key: '2026-09-16', label: '9.16' },
  { key: '2026-09-17', label: '9.17' },
  { key: '2026-09-18', label: '9.18' },
  { key: '2026-09-19', label: '9.19' },
  { key: '2026-09-20', label: '9.20' },
  { key: '2026-09-21', label: '9.21' }
];

const GIFT_CARD_DENOMINATIONS = [
  { faceValue: 100, salePrice: 100 },
  { faceValue: 200, salePrice: 200 },
  { faceValue: 500, salePrice: 500 }
];

function esc(v) {
  if (v === null || v === undefined) return 'NULL';
  return "'" + String(v).replace(/\\/g, '\\\\').replace(/'/g, "''") + "'";
}
function json(v) { return esc(JSON.stringify(v)); }
function cents(v) { return Math.round((Number(v) || 0) * 100); }

const L = [];
const push = (...xs) => xs.forEach(x => L.push(x));

push('-- =============================================================');
push('-- 小程序端业务数据落库（假数据清理 · 阶段 C）');
push('-- 由 server/scripts/gen-app-data-seed.cjs 自动生成，请勿手工编辑。');
push('--');
push('-- 背景：以下数据原先只存在于 user-h5/data/mock.js，前端直接 require 读取。');
push('-- 本迁移把它们补入数据库，前端改为走 /api/v1/app/** 接口读取。');
push('-- 原则：只补 DB 现有 seed 缺失的字段与内容，不覆盖运营已配置数据。');
push('--');
push('-- =============================================================');
push('');

// ---------- 1. product 详情扩展字段 ----------
push('-- ---------- 1. product 详情扩展字段（原 mock specDetail 内字段） ----------');
push('ALTER TABLE product');
push('    ADD COLUMN gallery_image    VARCHAR(255) NULL COMMENT "详情主图" AFTER image,');
push('    ADD COLUMN image_disclaimer VARCHAR(255) NULL COMMENT "图片免责声明" AFTER gallery_image,');
push('    ADD COLUMN promotion_text   VARCHAR(255) NULL COMMENT "促销文案" AFTER image_disclaimer,');
push('    ADD COLUMN price_label      VARCHAR(32)  NULL COMMENT "价格标签" AFTER promotion_text,');
push('    ADD COLUMN discount_rate    INT          NULL COMMENT "折扣万分比，10000=无折扣" AFTER price_label,');
push('    ADD COLUMN spec_tag         VARCHAR(64)  NULL COMMENT "详情页标签文案" AFTER discount_rate,');
push('    ADD COLUMN badge_icon       VARCHAR(255) NULL COMMENT "商品角标图标" AFTER spec_tag;');
push('');

const products = [];
for (const tab of catalog.menuTabs) for (const g of tab.groups) for (const c of g.categories) for (const p of c.products) products.push(p);

/** 生成 "col = CASE code WHEN .. THEN .. ELSE col END" 片段（积分商品用 code 关联）。 */
function caseByCode(column, list, pick) {
  const lines = ['  ' + column + ' = CASE code'];
  for (const item of list) lines.push('    WHEN ' + esc(item.id) + ' THEN ' + pick(item));
  lines.push('    ELSE ' + column + ' END');
  return lines.join('\n');
}

/** 生成 "col = CASE product_id WHEN .. THEN .. ELSE col END" 片段 */
function caseBlock(column, pick) {
  const lines = ['  ' + column + ' = CASE product_id'];
  for (const p of products) lines.push('    WHEN ' + esc(p.id) + ' THEN ' + pick(p));
  lines.push('    ELSE ' + column + ' END');
  return lines.join('\n');
}

push('UPDATE product SET');
push([
  caseBlock('gallery_image', p => esc(p.specDetail.galleryImage || p.image)),
  caseBlock('image_disclaimer', p => esc(p.specDetail.imageDisclaimer || '')),
  caseBlock('promotion_text', p => esc(p.specDetail.promotionText || '')),
  caseBlock('price_label', p => esc(p.specDetail.priceLabel || '')),
  caseBlock('discount_rate', p => String(Math.round((Number(p.specDetail.discountRate) || 1) * 10000))),
  caseBlock('spec_tag', p => esc(p.specDetail.tag || '')),
  caseBlock('badge_icon', p => esc(p.badgeIcon || ''))
].join(',\n'));
push(';');
push('');

// ---------- 2. store_profile 门店编码 ----------
push('-- ---------- 2. store_profile 门店业务编码（前端以 code 作为门店 id） ----------');
push('ALTER TABLE store_profile ADD COLUMN code VARCHAR(64) NULL COMMENT "门店业务编码" AFTER subject_id;');
push('');
const STORE_CODES = { 101: 'store-001', 102: 'store-002', 103: 'store-003', 104: 'store-004', 105: 'store-005' };
push('UPDATE store_profile SET code = CASE subject_id');
for (const [sid, code] of Object.entries(STORE_CODES)) push('    WHEN ' + sid + ' THEN ' + esc(code));
push('    ELSE code END;');
push('');

// ---------- 3. points_product 分类与券字段 ----------
push('-- ---------- 3. points_product 分类与券展示字段 ----------');
push('ALTER TABLE points_product');
push('    ADD COLUMN category         VARCHAR(32)  NULL COMMENT "分类 all/pet/coupon" AFTER description,');
push('    ADD COLUMN purchase_limit   INT          NOT NULL DEFAULT 0 COMMENT "每人限购 0=不限" AFTER category,');
push('    ADD COLUMN display_type     VARCHAR(32)  NULL COMMENT "券类型 fixed/buyone/halfprice" AFTER purchase_limit,');
push('    ADD COLUMN coupon_amount    BIGINT       NOT NULL DEFAULT 0 COMMENT "券面额(分)" AFTER display_type,');
push('    ADD COLUMN coupon_condition VARCHAR(64)  NULL COMMENT "券门槛文案" AFTER coupon_amount,');
push('    ADD COLUMN badge_in_image   TINYINT      NOT NULL DEFAULT 0 COMMENT "角标叠加在图片上" AFTER badge;');
push('');
push('-- V6 已初始化 2 行（points-pet-food / points-coupon-3），这里补齐分类与券字段。');
push('UPDATE points_product SET');
push([
  caseByCode('category', POINTS_PRODUCTS, p => esc(p.category)),
  caseByCode('purchase_limit', POINTS_PRODUCTS, p => String(Number(p.purchaseLimit) || 0)),
  caseByCode('display_type', POINTS_PRODUCTS, p => esc(p.displayType || 'fixed')),
  caseByCode('coupon_amount', POINTS_PRODUCTS, p => String(cents(p.amount))),
  caseByCode('coupon_condition', POINTS_PRODUCTS, p => esc(p.condition || '不限')),
  caseByCode('badge_in_image', POINTS_PRODUCTS, p => (p.badgeInImage ? '1' : '0'))
].join(',\n'));
push(';');
push('');
push('-- 补充小程序展出的兑换商品（与设计稿 4 张商品卡一致）');
push('INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES');
push(EXTRA_POINTS_PRODUCTS.map(row => '(' + row.join(', ') + ')').join(',\n'));
push('ON DUPLICATE KEY UPDATE name=VALUES(name), image=VALUES(image), points=VALUES(points), stock=VALUES(stock),');
push('  badge=VALUES(badge), limit_text=VALUES(limit_text), description=VALUES(description), category=VALUES(category),');
push('  purchase_limit=VALUES(purchase_limit), display_type=VALUES(display_type), coupon_amount=VALUES(coupon_amount),');
push('  coupon_condition=VALUES(coupon_condition), badge_in_image=VALUES(badge_in_image);');
push('');
// ---------- 4. gift_card_denomination ----------
push('-- ---------- 4. gift_card_denomination 分组与展示信息（礼品卡页分组展示依赖） ----------');
push('ALTER TABLE gift_card_denomination');
push('    ADD COLUMN group_id    VARCHAR(32)  NULL COMMENT "分组 id" AFTER code,');
push('    ADD COLUMN group_title VARCHAR(64)  NULL COMMENT "分组标题" AFTER group_id,');
push('    ADD COLUMN card_name   VARCHAR(64)  NULL COMMENT "卡面名称" AFTER group_title,');
push('    ADD COLUMN card_image  VARCHAR(255) NULL COMMENT "卡面图片" AFTER card_name,');
push('    ADD COLUMN sale_price  BIGINT       NOT NULL DEFAULT 0 COMMENT "售价(分)" AFTER amount,');
push('    ADD COLUMN sort        INT          NOT NULL DEFAULT 0 COMMENT "排序" AFTER sale_price;');
push('');
push('-- 原 3 条按面额的占位数据改为「分组 x 面额」的完整卡面清单，先移除旧占位行。');
push("DELETE FROM gift_card_denomination WHERE code IN ('gift-value-100', 'gift-value-200', 'gift-value-500');");
push('');
push('INSERT INTO gift_card_denomination (code, group_id, group_title, card_name, card_image, name, amount, sale_price, status, sort) VALUES');
const gcRows = [];
let gcSort = 0;
for (const g of GIFT_CARD_GROUPS) {
  for (const card of g.cards) {
    for (const denom of GIFT_CARD_DENOMINATIONS) {
      const face = denom.faceValue;
      const faceCents = cents(face);
      const code = card.id + '-' + face;
      const name = card.name + ' ' + face + '元礼品卡';
      gcRows.push('(' + [esc(code), esc(g.id), esc(g.title), esc(card.name), esc(card.image), esc(name), faceCents, faceCents, esc('enabled'), gcSort++].join(', ') + ')');
    }
  }
}
push(gcRows.join(',\n'));
push('ON DUPLICATE KEY UPDATE group_id=VALUES(group_id), group_title=VALUES(group_title), card_name=VALUES(card_name),');
push('  card_image=VALUES(card_image), name=VALUES(name), amount=VALUES(amount), sale_price=VALUES(sale_price), sort=VALUES(sort);');
push('');

// ---------- 5. app_config ----------
push('-- ---------- 5. app_config 补齐小程序运营配置 ----------');
push('-- home_shortcuts / menu_activity / profile_functions / signin_rules / signin_rewards / app_cities');
push('-- 已由 V10 初始化，这里仅补签到日历基准数据。');
push('');
push('INSERT INTO app_config (config_key, config_name, value, sort, remark) VALUES');
push("('points_signin', '签到日历与奖励', " + json({
  year: 2026, month: 9, today: '2026-09-17', todayLabel: '9.17',
  weekDates: POINTS_SIGNIN_WEEK_DATES
}) + ", 7, '签到页日历基准数据')");
push('ON DUPLICATE KEY UPDATE config_name=VALUES(config_name), value=VALUES(value), sort=VALUES(sort), remark=VALUES(remark);');
push('');

fs.writeFileSync(path.join(__dirname, '../src/main/resources/db/migration/V16__seed_app_data.sql'), L.join('\n') + '\n', 'utf8');
console.log('V16 written. products=' + products.length + ', points=' + POINTS_PRODUCTS.length + ', giftCards=' + gcRows.length);
