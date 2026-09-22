const fs = require("fs");
const s = fs.readFileSync("server/src/main/resources/db/migration/V16__seed_app_data.sql", "utf8");
console.log("lines:", s.split("\n").length);
console.log("semicolons:", (s.match(/;/g) || []).length);
console.log("badge_icon col:", /badge_icon\s+VARCHAR/.test(s));
console.log("astray CASE commas:", (s.match(/CASE \w+,\n/g) || []).length);
const m = s.match(/INSERT INTO points_product[\s\S]*?VALUES\n([\s\S]*?)\nON DUPLICATE/);
console.log("points rows:", m ? m[1].split("\n").length : "none");
