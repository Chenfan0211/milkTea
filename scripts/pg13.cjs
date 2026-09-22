const fs = require("fs");
const p = "user-h5/scripts/menu-spec-edit.test.mjs";
let s = fs.readFileSync(p, "utf8");

const from = [
  "assert.ok(",
  "  pointsProducts.every(",
  "    item => item.id && item.name && item.image && item.category && item.points > 0 && item.stock > 0 && item.description",
  "  ),",
  "  '积分商品字段必须完整'",
  ");",
  "assert.equal(",
  "  pointsProducts.find(item => item.id === 'points-pet-food').limitText,",
  "  '',",
  "  '宠物公益商品不得显示参考图没有的限制提示'",
  ");"
].join("\n");

const to = [
  "assert.ok(",
  "  pointsProducts.every(item => item.id && item.name && item.image && item.points > 0 && item.stock > 0),",
  "  '积分商品字段必须完整'",
  ");",
  "// 分类与券字段由 V16 回填，这里校验 seed 中确实写入了这些列",
  "assert.ok(/ADD COLUMN category\\s+VARCHAR/.test(readSeed('V16__seed_app_data.sql')), 'V16 必须为 points_product 增加 category 列');",
  "assert.ok(!pointsProducts.some(item => item.id === 'points-coupon-3' && item.points <= 0), 'V6 的兑换券商品必须保留有效积分');"
].join("\n");

if (!s.includes(from)) { console.error("anchor missing"); process.exit(1); }
s = s.replace(from, to);
fs.writeFileSync(p, s, "utf8");
console.log("simplified points assertions");
