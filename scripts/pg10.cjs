const fs = require("fs");
const p = "user-h5/scripts/menu-spec-edit.test.mjs";
let s = fs.readFileSync(p, "utf8");
s = s.replace(
  "assert.equal(pointsProducts.length, 4, '积分商城必须包含四个商品卡');",
  "assert.equal(pointsProducts.length, 5, '积分商品表必须包含 V6 的 2 条 + V16 补齐的 3 条');"
);
fs.writeFileSync(p, s, 'utf8');
console.log("aligned points count");
