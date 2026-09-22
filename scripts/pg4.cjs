const fs = require('fs');
const p = 'server/scripts/gen-app-data-seed.cjs';
let s = fs.readFileSync(p, 'utf8');

// 1) 补 badge_icon 的 ALTER 列
s = s.replace(
  "push('    ADD COLUMN spec_tag         VARCHAR(64)  NULL COMMENT \"详情页标签文案\" AFTER discount_rate;');",
  [
    "push('    ADD COLUMN spec_tag         VARCHAR(64)  NULL COMMENT \"详情页标签文案\" AFTER discount_rate,');",
    "push('    ADD COLUMN badge_icon       VARCHAR(255) NULL COMMENT \"商品角标图标\" AFTER spec_tag;');"
  ].join('\n')
);

// 2) 补 badge_icon 的 CASE 更新
s = s.replace(
  "  caseBlock('spec_tag', p => esc(p.specDetail.tag || ''))",
  "  caseBlock('spec_tag', p => esc(p.specDetail.tag || '')),\n  caseBlock('badge_icon', p => esc(p.badgeIcon || ''))"
);

fs.writeFileSync(p, s, 'utf8');
console.log('has ALTER:', s.includes('badge_icon       VARCHAR'));
console.log('has CASE:', s.includes("caseBlock('badge_icon'"));
