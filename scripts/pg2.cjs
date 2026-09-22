const fs = require('fs');
const p = 'server/scripts/gen-app-data-seed.cjs';
let s = fs.readFileSync(p, 'utf8');

// 1) 积分商品：mock.pointsProducts -> POINTS_PRODUCTS（desc/name 等来自 V6 已存在行，这里只补元数据）
s = s.replace(
  "push(mock.pointsProducts.map(p => {\n  const id = KNOWN[p.id] || (nextId++);\n  return '(' + [id, esc(p.id), esc(p.name), esc(p.image), p.points, p.stock, esc(p.badge || ''), esc(p.limitText || ''),\n    esc(p.description), esc('enabled'), esc(p.category), Number(p.purchaseLimit) || 0, esc(p.displayType || 'fixed'),\n    cents(p.amount), esc(p.condition || '不限'), p.badgeInImage ? 1 : 0].join(', ') + ')';\n}).join(',\\n'));",
  "// 仅对 V6 已有的积分商品补分类/券字段；完整商品行由 V6 维护。\npush('-- 说明：points_product 的商品行由 V6 初始化，这里补齐分类与券展示字段。');\npush('UPDATE points_product SET');\npush('  category = CASE code');\nfor (const p of POINTS_PRODUCTS) push('    WHEN ' + esc(p.id) + ' THEN ' + esc(p.category));\npush('    ELSE category END,');\npush('  purchase_limit = CASE code');\nfor (const p of POINTS_PRODUCTS) push('    WHEN ' + esc(p.id) + ' THEN ' + (Number(p.purchaseLimit) || 0));\npush('    ELSE purchase_limit END,');\npush('  display_type = CASE code');\nfor (const p of POINTS_PRODUCTS) push('    WHEN ' + esc(p.id) + ' THEN ' + esc(p.displayType || 'fixed'));\npush('    ELSE display_type END,');\npush('  coupon_amount = CASE code');\nfor (const p of POINTS_PRODUCTS) push('    WHEN ' + esc(p.id) + ' THEN ' + cents(p.amount));\npush('    ELSE coupon_amount END,');\npush('  coupon_condition = CASE code');\nfor (const p of POINTS_PRODUCTS) push('    WHEN ' + esc(p.id) + ' THEN ' + esc(p.condition || '不限'));\npush('    ELSE coupon_condition END,');\npush('  badge_in_image = CASE code');\nfor (const p of POINTS_PRODUCTS) push('    WHEN ' + esc(p.id) + ' THEN ' + (p.badgeInImage ? 1 : 0));\npush('    ELSE badge_in_image END;');\npush('');\n// 小程序展出的 4 款兑换商品：缺失的 2 条按 V6 的公仔/券风格补齐\npush('INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES');\npush([\n  \"(100, 'points-matcha-buy-one', '超浓抹茶系列买一送一券', '/assets/images/3x/points-product-matcha.jpg', 300, 51, '限时抢兑', '*每人仅可兑换一次', '超浓抹茶系列买一送一券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。', 'enabled', 'coupon', 1, 'buyone', 0, '不限', 0)\",\n  \"(101, 'points-single-cup', '超浓抹茶系列单杯3元券', '/assets/images/3x/points-product-single.jpg', 300, 51, '限时抢兑', '*每人仅可兑换一次', '超浓抹茶系列单杯3元优惠券，兑换后可在指定饮品结算时抵扣，具体适用范围和有效期以券面说明为准。', 'enabled', 'coupon', 1, 'fixed', 300, '不限', 1)\",\n  \"(102, 'points-second-cup-half', '超浓抹茶系列第2杯半价券', '/assets/images/3x/points-product-half.jpg', 300, 51, '限时抢兑', '*每人仅可兑换一次', '超浓抹茶系列第2杯半价券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。', 'enabled', 'coupon', 1, 'halfprice', 0, '不限', 1)\"\n].join(',\\n'));\npush('ON DUPLICATE KEY UPDATE name=VALUES(name), image=VALUES(image), points=VALUES(points), stock=VALUES(stock),');\npush('  badge=VALUES(badge), limit_text=VALUES(limit_text), description=VALUES(description), category=VALUES(category),');\npush('  purchase_limit=VALUES(purchase_limit), display_type=VALUES(display_type), coupon_amount=VALUES(coupon_amount),');\npush('  coupon_condition=VALUES(coupon_condition), badge_in_image=VALUES(badge_in_image);');"
);

// 2) 礼品卡：mock.giftCardGroups / giftCardDenominations -> 常量
s = s.replace(
  "for (const g of mock.giftCardGroups) {",
  "for (const g of GIFT_CARD_GROUPS) {"
);
s = s.replace(
  "    for (const denom of mock.giftCardDenominations) {",
  "    for (const denom of GIFT_CARD_DENOMINATIONS) {"
);

// 3) 签到日历：mock.pointsSignIn -> 常量
s = s.replace(
  "  weekDates: mock.pointsSignIn.weekDates",
  "  weekDates: POINTS_SIGNIN_WEEK_DATES"
);
s = s.replace(
  "const GIFT_CARD_DENOMINATIONS = [",
  [
    "const POINTS_SIGNIN_WEEK_DATES = [",
    "  { key: '2026-09-16', label: '9.16' },",
    "  { key: '2026-09-17', label: '9.17' },",
    "  { key: '2026-09-18', label: '9.18' },",
    "  { key: '2026-09-19', label: '9.19' },",
    "  { key: '2026-09-20', label: '9.20' },",
    "  { key: '2026-09-21', label: '9.21' }",
    "];",
    "",
    "const GIFT_CARD_DENOMINATIONS = ["
  ].join('\n')
);

fs.writeFileSync(p, s, 'utf8');
console.log('remaining mock refs:', (s.match(/mock\./g) || []).length);
