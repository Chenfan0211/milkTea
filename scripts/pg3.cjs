const fs = require('fs');
const p = 'server/scripts/gen-app-data-seed.cjs';
let s = fs.readFileSync(p, 'utf8');
s = s.replace(
  "console.log('V16 written. products=' + products.length + ', points=' + mock.pointsProducts.length + ', giftCards=' + gcRows.length);",
  "console.log('V16 written. products=' + products.length + ', points=' + POINTS_PRODUCTS.length + ', giftCards=' + gcRows.length);"
);
fs.writeFileSync(p, s, 'utf8');
console.log('remaining mock refs:', (s.match(/mock\./g) || []).length);
