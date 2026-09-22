const fs = require("fs");
const p = "user-h5/scripts/menu-spec-edit.test.mjs";
let s = fs.readFileSync(p, "utf8");
const from = "const pointsProductSql = readSeed('V16__seed_app_data.sql').match(/INSERT INTO points_product \\(id, code[\\s\\S]*?badge_in_image=VALUES\\(badge_in_image\\);/)[0];";
if (!s.includes(from)) { console.error("anchor missing"); process.exit(1); }
s = s.replace(from, [
  "// V6 初始化 2 条，V16 补齐 3 条（并把分类/券字段回填）",
  "const pointsProductSql = readSeed('V6__seed_marketing.sql').match(/INSERT INTO points_product \\(id, code[\\s\\S]*?;/)[0]",
  "  + readSeed('V16__seed_app_data.sql').match(/INSERT INTO points_product \\(id, code[\\s\\S]*?badge_in_image=VALUES\\(badge_in_image\\);/)[0];"
].join("\n"));
fs.writeFileSync(p, s, "utf8");
console.log("restored V6+V16 concat");
