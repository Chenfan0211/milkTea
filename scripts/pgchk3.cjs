const fs = require("fs");
const DIR = "server/src/main/resources/db/migration";
const v6 = fs.readFileSync(DIR + "/V6__seed_marketing.sql", "utf8");
const v16 = fs.readFileSync(DIR + "/V16__seed_app_data.sql", "utf8");
const block = v16.match(/INSERT INTO points_product \(id, code[\s\S]*?badge_in_image=VALUES\(badge_in_image\);/)[0];
const combined = v6.match(/INSERT INTO points_product \(id, code[\s\S]*?;/)[0] + block;
const rows = [...combined.matchAll(/\((\d+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*(\d+),\s*(\d+)/g)];
console.log("raw:", rows.length, rows.map(r => r[2]).join(", "));
