import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const homeDir = path.join(root, 'packageMall', 'pages', 'home');
const read = name => fs.readFileSync(path.join(homeDir, `${name}`), 'utf8');
const wxml = read('home.wxml');
const wxss = read('home.wxss');
const js = read('home.js');

// 轻奢白卡风：品牌区、快捷卡、商品卡、空态都必须重组为白卡结构。
assert.match(wxml, /home-hero__badge/, '品牌信息区必须升级为白卡徽标结构');
assert.match(wxml, /home-quick-card/, '购物车与订单必须使用横向快捷卡');
assert.match(wxml, /home-quick-card__description/, '快捷卡必须包含说明文案');
assert.match(wxml, /该分类暂无商品/, '分类无商品时必须有明确空态');
assert.match(wxml, /刷新看看/, '空态必须提供刷新操作');
assert.match(wxml, /product-card__stock-row/, '商品卡必须有库存状态行');
assert.match(wxss, /border:\s*1rpx solid var\(--line-color\)/, '白卡必须使用设计 token 边框');
assert.match(wxss, /box-shadow:\s*var\(--shadow-card\)/, '白卡必须使用设计 token 阴影');
assert.doesNotMatch(wxss, /#[0-9A-Fa-f]{3,8}\b/, 'home.wxss 不得新增颜色字面量');
assert.doesNotMatch(
  wxss,
  /background:\s*var\(--brand-soft\);\s*border-radius:\s*var\(--radius-lg\);[\s\S]*hero__description/,
  '旧绿色促销块必须移除'
);

// 交互与路由：分类切换、商品详情、购物车、订单都不能回归。
assert.match(js, /selectCategory/, '分类切换逻辑必须保留');
assert.match(js, /Number\(event\.currentTarget\.dataset\.id\)/, '分类点击必须读取 dataset id');
assert.match(js, /fetchMallProducts\(\{ page, size: PAGE_SIZE, categoryId \}\)/, '分类筛选必须传 categoryId');
assert.match(js, /\/packageMall\/pages\/product-detail\/product-detail\?id=/, '商品卡必须进入详情页');
assert.match(wxml, /bindtap="openCart"/, '购物车快捷卡必须保留点击');
assert.match(wxml, /bindtap="openOrders"/, '订单快捷卡必须保留点击');

// 可点击节点必须有无障碍声明。
for (const match of wxml.matchAll(/<view\b[^>]*bindtap="[^"]+"[^>]*>/g)) {
  assert.match(match[0], /aria-role="button"/, `可点击 view 缺少 aria-role: ${match[0]}`);
  assert.match(match[0], /aria-label=/, `可点击 view 缺少 aria-label: ${match[0]}`);
}

// 样式合规：只允许 rpx，间距必须来自设计档位。
assert.doesNotMatch(wxss, /\b\d+(?:\.\d+)?px\b/, 'home.wxss 必须使用 rpx');
const allowedSpacing = new Set(['0', '1', '4', '8', '12', '16', '20', '24', '32', '40', '52', '64', '80', '104']);
for (const line of wxss.split('\n')) {
  if (!/^\s*(?:padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|top|right|bottom|left|gap)\s*:/.test(line)) continue;
  for (const match of line.matchAll(/(?<![\d.])(\d+)rpx/g)) {
    assert.ok(allowedSpacing.has(match[1]), `间距必须使用设计档位：${line.trim()}`);
  }
}

console.log('商城首页轻奢白卡风改版测试通过');
