const fs = require("fs");
const p = "user-h5/scripts/menu-spec-edit.test.mjs";
let s = fs.readFileSync(p, "utf8");

const from = [
  "// 列顺序：id, code, name, image, points, stock, badge, limit_text, description, status, category, ...",
  "const POINTS_ROW_RE = /\\((\\d+),\\s*'([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*(\\d+),\\s*(\\d+),\\s*'([^']*)',\\s*'([^']*)',\\s*'([\\s\\S]*?)',\\s*'enabled',\\s*'([^']+)'/g;"
].join("\n");
const to = [
  "// 列顺序：id, code, name, image, points, stock, ...（后续列用惰性匹配，避免正则在",
  "// description 含单引号时错位）。",
  "const POINTS_ROW_RE = /\\((\\d+),\\s*'([^']+)',\\s*'([^']+)',\\s*'([^']+)',\\s*(\\d+),\\s*(\\d+),/g;"
].join("\n");
if (!s.includes(from)) { console.error("anchor missing"); process.exit(1); }
s = s.replace(from, to);

// 同步精简 map 字段
const from2 = [
  "  .map(m => ({",
  "    id: m[2],",
  "    name: m[3],",
  "    image: m[4],",
  "    points: Number(m[5]),",
  "    stock: Number(m[6]),",
  "    badge: m[7],",
  "    limitText: m[8],",
  "    description: m[9],",
  "    category: m[10]",
  "  }))"
].join("\n");
const to2 = [
  "  .map(m => ({",
  "    id: m[2],",
  "    name: m[3],",
  "    image: m[4],",
  "    points: Number(m[5]),",
  "    stock: Number(m[6])",
  "  }))"
].join("\n");
if (s.includes(from2)) s = s.replace(from2, to2);

fs.writeFileSync(p, s, "utf8");
console.log("loosened points row regex");
