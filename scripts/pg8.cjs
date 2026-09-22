const fs = require('fs');
const p = 'server/scripts/gen-app-data-seed.cjs';
let s = fs.readFileSync(p, 'utf8');

// 删除遗留的 INSERT 头（已改为 UPDATE + 3 行 INSERT 的写法）
s = s.replace(
  "push('INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES');\npush('-- 说明：points_product 的商品行由 V6 初始化，这里补齐分类与券展示字段。');",
  "push('-- 说明：points_product 的前 2 行由 V6 初始化，这里补齐分类与券展示字段，');\npush('--       并补充小程序展出的其余兑换商品。');"
);

// 删除重复的 ON DUPLICATE（保留最后一份）
s = s.replace(
  "push('ON DUPLICATE KEY UPDATE name=VALUES(name), image=VALUES(image), points=VALUES(points), stock=VALUES(stock),');\npush('  badge=VALUES(badge), limit_text=VALUES(limit_text), description=VALUES(description), category=VALUES(category),');\npush('  purchase_limit=VALUES(purchase_limit), display_type=VALUES(display_type), coupon_amount=VALUES(coupon_amount),');\npush('  coupon_condition=VALUES(coupon_condition), badge_in_image=VALUES(badge_in_image);');\npush('');\npush('INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES');",
  "push('INSERT INTO points_product (id, code, name, image, points, stock, badge, limit_text, description, status, category, purchase_limit, display_type, coupon_amount, coupon_condition, badge_in_image) VALUES');"
);

fs.writeFileSync(p, s, 'utf8');
console.log('cleaned generator');
