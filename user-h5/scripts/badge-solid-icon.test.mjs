import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const GOLD = '#D4A017';

const goldIconPath = path.join(root, 'assets/icons/lucide/member-gold.svg');
assert.ok(fs.existsSync(goldIconPath), 'missing member-gold.svg');
const goldSvg = fs.readFileSync(goldIconPath, 'utf8');
assert.ok(goldSvg.includes(`fill="${GOLD}"`), 'solid badge must be filled with member gold');
assert.ok(goldSvg.includes(`stroke="${GOLD}"`), 'solid badge must be stroked with member gold');
assert.ok(!goldSvg.includes('fill="none"'), 'solid badge must not stay an outline icon');
assert.ok(!goldSvg.includes('currentColor'), 'icon color must be fixed');

const outlineIconPath = path.join(root, 'assets/icons/lucide/member.svg');
assert.ok(fs.existsSync(outlineIconPath), 'original member.svg must remain');
const outlineSvg = fs.readFileSync(outlineIconPath, 'utf8');
assert.ok(outlineSvg.includes('fill="none"'), 'original member.svg must stay an outline');

const iconScript = fs.readFileSync(path.join(root, 'scripts/sync-lucide-icons.mjs'), 'utf8');
assert.ok(
  /output:\s*'member-gold'[\s\S]*?color:\s*'#D4A017'[\s\S]*?fill:\s*true/.test(iconScript),
  'icon registry must generate member-gold with fill: true'
);
// 4. 数据源：商品角标指向实心图标
const mockSource = fs.readFileSync(path.join(root, 'data/mock.js'), 'utf8');
assert.ok(
  /badgeMemberIcon = '\/assets\/icons\/lucide\/member-gold\.svg'/.test(mockSource),
  'badgeMemberIcon must point at the solid gold icon'
);
assert.ok(
  !/badgeMemberIcon = '\/assets\/icons\/lucide\/member\.svg'/.test(mockSource),
  'badgeMemberIcon must not point at the old outline icon'
);

const { menuTabs } = require(path.join(root, 'data/mock.js'));
const badgedProducts = [];
for (const group of menuTabs) {
  for (const category of group.groups || []) {
    for (const cat of category.categories || []) {
      for (const product of cat.products || []) {
        if (product.badgeIcon) badgedProducts.push(product);
      }
    }
  }
}
assert.ok(badgedProducts.length > 0, 'there must be at least one badged product');
for (const product of badgedProducts) {
  assert.equal(
    product.badgeIcon,
    '/assets/icons/lucide/member-gold.svg',
    `${product.id} badge must use the solid gold icon`
  );
}
// 5. 三处复用仍由 badgeIcon 驱动，且位置保持在图片右上角
const reusePoints = [
  ['components/product-card/product-card.wxml', 'components/product-card/product-card.wxss', '.product-card__badge'],
  ['components/spec-sheet/spec-sheet.wxml', 'components/spec-sheet/spec-sheet.wxss', '.spec-sheet__hero-badge'],
  ['pages/order-detail/order-detail.wxml', 'pages/order-detail/order-detail.wxss', '.product-row__crown']
];
for (const [wxmlPath, wxssPath, selector] of reusePoints) {
  const wxml = fs.readFileSync(path.join(root, wxmlPath), 'utf8');
  assert.ok(
    wxml.includes('wx:if="{{product.badgeIcon}}"') || wxml.includes('wx:if="{{item.badgeIcon}}"'),
    `${wxmlPath} badge must stay data driven`
  );
  const wxss = fs.readFileSync(path.join(root, wxssPath), 'utf8');
  const rule =
    wxss.match(new RegExp(selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{([\\s\\S]*?)\\}'))?.[1] || '';
  assert.ok(/right:\s*-4rpx/.test(rule), `${selector} must stay pinned to the image top-right corner`);
}

// 6. 其他角标统一为设计 token，不再硬编码颜色
const confirmWxss = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.wxss'), 'utf8');
const confirmBadge = confirmWxss.match(/\.goods-row__badge\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(confirmBadge.includes('var(--favorite-gold)'), 'order-confirm new badge must use the member gold token');

const cartWxss = fs.readFileSync(path.join(root, 'components/cart-bar/cart-bar.wxss'), 'utf8');
assert.ok(!cartWxss.includes('#5A9634'), 'cart badge must no longer hardcode #5A9634');
const cartBadge = cartWxss.match(/\.cart-bar__badge\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(cartBadge.includes('var(--brand-green)'), 'cart badge must use the brand token');

console.log('商品角标实心化、图标复用与角标 token 统一测试通过');
