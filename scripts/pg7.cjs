const fs = require('fs');
const p = 'user-h5/scripts/badge-solid-icon.test.mjs';
let s = fs.readFileSync(p, 'utf8');

const startMark = '// 4. 数据源：商品角标指向实心图标';
const endMark = "assert.ok(badgedProducts.length > 0, 'there must be at least one badged product');";
const a = s.indexOf(startMark);
const b = s.indexOf(endMark);
if (a < 0 || b < 0) { console.error('anchors missing', a, b); process.exit(1); }

const block = [
  '// 4. 数据源：商品角标来自数据库 seed（V16 的 product.badge_icon）',
  "const { loadMenu, readSeed } = await import('./lib/seed-data.mjs');",
  "const seedSql = readSeed('V16__seed_app_data.sql');",
  'assert.ok(',
  '  /ADD COLUMN badge_icon\\s+VARCHAR/.test(seedSql),',
  "  'V16 must add product.badge_icon column'",
  ');',
  'const badgeBlock = seedSql.match(/badge_icon = CASE product_id([\\s\\S]*?)ELSE badge_icon END/);',
  "assert.ok(badgeBlock, 'V16 must initialize product badges');",
  'assert.ok(',
  "  badgeBlock[1].includes('/assets/icons/lucide/member-gold.svg'),",
  "  'badge_icon must point at the solid gold icon'",
  ');',
  'assert.ok(',
  "  !badgeBlock[1].includes(\"/assets/icons/lucide/member.svg'\"),",
  "  'badge_icon must not point at the old outline icon'",
  ');',
  '',
  '// 角标商品：seed 中标记了 badge_icon 的商品',
  'const badgedIds = [...badgeBlock[1].matchAll(/WHEN \'([^\']+)\' THEN \'([^\']+)\'/g)]',
  '  .filter(m => m[2])',
  '  .map(m => m[1]);',
  'const menuTabs = loadMenu();',
  'const badgedProducts = [];',
  'for (const tab of menuTabs) {',
  '  for (const group of tab.groups || []) {',
  '    for (const cat of group.categories || []) {',
  '      for (const product of cat.products || []) {',
  '        if (badgedIds.includes(product.id)) {',
  "          badgedProducts.push({ id: product.id, badgeIcon: '/assets/icons/lucide/member-gold.svg' });",
  '        }',
  '      }',
  '    }',
  '  }',
  '}',
  ''
].join('\n');

s = s.slice(0, a) + block + s.slice(b);
fs.writeFileSync(p, s, 'utf8');
console.log('spliced badge test data source');
