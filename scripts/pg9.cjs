const fs = require('fs');
const p = 'server/scripts/gen-app-data-seed.cjs';
let s = fs.readFileSync(p, 'utf8');

const start = s.indexOf("// ---------- 3. points_product ----------");
const end = s.indexOf("// ---------- 4. gift_card_denomination ----------");
if (start < 0 || end < 0) { console.error('anchors missing', start, end); process.exit(1); }

const block = [
  '// ---------- 3. points_product 分类与券字段 ----------',
  "push('-- ---------- 3. points_product 分类与券展示字段 ----------');",
  "push('ALTER TABLE points_product');",
  "push('    ADD COLUMN category         VARCHAR(32)  NULL COMMENT \"分类 all/pet/coupon\" AFTER description,');",
  "push('    ADD COLUMN purchase_limit   INT          NOT NULL DEFAULT 0 COMMENT \"每人限购 0=不限\" AFTER category,');",
  "push('    ADD COLUMN display_type     VARCHAR(32)  NULL COMMENT \"券类型 fixed/buyone/halfprice\" AFTER purchase_limit,');",
  "push('    ADD COLUMN coupon_amount    BIGINT       NOT NULL DEFAULT 0 COMMENT \"券面额(分)\" AFTER display_type,');",
  "push('    ADD COLUMN coupon_condition VARCHAR(64)  NULL COMMENT \"券门槛文案\" AFTER coupon_amount,');",
  "push('    ADD COLUMN badge_in_image   TINYINT      NOT NULL DEFAULT 0 COMMENT \"角标叠加在图片上\" AFTER badge;');",
  "push('');",
  "push('-- V6 已初始化 2 行（points-pet-food / points-coupon-3），这里补齐分类与券字段。');",
  "push('UPDATE points_product SET');",
  "push([",
  "  caseByCode('category', POINTS_PRODUCTS, p => esc(p.category)),",
  "  caseByCode('purchase_limit', POINTS_PRODUCTS, p => String(Number(p.purchaseLimit) || 0)),",
  "  caseByCode('display_type', POINTS_PRODUCTS, p => esc(p.displayType || 'fixed')),",
  "  caseByCode('coupon_amount', POINTS_PRODUCTS, p => String(cents(p.amount))),",
  "  caseByCode('coupon_condition', POINTS_PRODUCTS, p => esc(p.condition || '不限')),",
  "  caseByCode('badge_in_image', POINTS_PRODUCTS, p => (p.badgeInImage ? '1' : '0'))",
  "].join(',\\n'));",
  "push(';');",
  "push('');",
  "push('-- 补充小程序展出的兑换商品（与设计稿 4 张商品卡一致）');",
  "push('INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES');",
  "push(EXTRA_POINTS_PRODUCTS.map(row => '(' + row.join(', ') + ')').join(',\\n'));",
  "push('ON DUPLICATE KEY UPDATE name=VALUES(name), image=VALUES(image), points=VALUES(points), stock=VALUES(stock),');",
  "push('  badge=VALUES(badge), limit_text=VALUES(limit_text), description=VALUES(description), category=VALUES(category),');",
  "push('  purchase_limit=VALUES(purchase_limit), display_type=VALUES(display_type), coupon_amount=VALUES(coupon_amount),');",
  "push('  coupon_condition=VALUES(coupon_condition), badge_in_image=VALUES(badge_in_image);');",
  "push('');",
  ""
].join('\n');

s = s.slice(0, start) + block + s.slice(end);

// 追加辅助函数与常量
s = s.replace(
  "/** 生成 \"col = CASE product_id WHEN .. THEN .. ELSE col END\" 片段 */",
  [
    "/** 生成 \"col = CASE code WHEN .. THEN .. ELSE col END\" 片段（积分商品用 code 关联）。 */",
    "function caseByCode(column, list, pick) {",
    "  const lines = ['  ' + column + ' = CASE code'];",
    "  for (const item of list) lines.push('    WHEN ' + esc(item.id) + ' THEN ' + pick(item));",
    "  lines.push('    ELSE ' + column + ' END');",
    "  return lines.join('\\n');",
    "}",
    "",
    "/** 生成 \"col = CASE product_id WHEN .. THEN .. ELSE col END\" 片段 */"
  ].join('\n')
);

// 常量：补齐的 3 条积分商品
s = s.replace(
  "const GIFT_CARD_GROUPS = [",
  [
    "// 需要补入的积分商品（V6 已含 points-pet-food 与 points-coupon-3）",
    "const EXTRA_POINTS_PRODUCTS = [",
    "  [100, \"'points-matcha-buy-one'\", \"'超浓抹茶系列买一送一券'\", \"'/assets/images/3x/points-product-matcha.jpg'\", 300, 51, \"'限时抢兑'\", \"'*每人仅可兑换一次'\",",
    "    \"'超浓抹茶系列买一送一券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。'\", \"'enabled'\", \"'coupon'\", 1, \"'buyone'\", 0, \"'不限'\", 0],",
    "  [101, \"'points-single-cup'\", \"'超浓抹茶系列单杯3元券'\", \"'/assets/images/3x/points-product-single.jpg'\", 300, 51, \"'限时抢兑'\", \"'*每人仅可兑换一次'\",",
    "    \"'超浓抹茶系列单杯3元优惠券，兑换后可在指定饮品结算时抵扣，具体适用范围和有效期以券面说明为准。'\", \"'enabled'\", \"'coupon'\", 1, \"'fixed'\", 300, \"'不限'\", 1],",
    "  [102, \"'points-second-cup-half'\", \"'超浓抹茶系列第2杯半价券'\", \"'/assets/images/3x/points-product-half.jpg'\", 300, 51, \"'限时抢兑'\", \"'*每人仅可兑换一次'\",",
    "    \"'超浓抹茶系列第2杯半价券，兑换后可在指定饮品结算时使用，具体适用范围和有效期以券面说明为准。'\", \"'enabled'\", \"'coupon'\", 1, \"'halfprice'\", 0, \"'不限'\", 1]",
    "];",
    "",
    "const GIFT_CARD_GROUPS = ["
  ].join('\n')
);

fs.writeFileSync(p, s, 'utf8');
console.log('rewrote points section');
