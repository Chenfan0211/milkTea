import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const toasts = [];
const switches = [];
const app = { globalData: {} };
let capturedPage = null;
globalThis.Page = options => {
  capturedPage = options;
};
globalThis.getApp = () => app;
globalThis.wx = {
  showToast(options) {
    toasts.push(options.title);
  },
  showLoading() {},
  hideLoading() {},
  switchTab(options) {
    switches.push(options.url);
  },
  navigateTo() {},
  getStorageSync() {
    return '';
  },
  setStorageSync() {},
  removeStorageSync() {}
};

require(path.join(root, 'pages/menu/menu.js'));
const productListing = require(path.join(root, 'utils/product-listing.js'));
const recommendation = require(path.join(root, 'utils/recommendation-cart.js'));

function menuWith(products) {
  return [
    {
      id: 'menu',
      label: '点单',
      groups: [{ id: 'all', label: '全部', categories: [{ id: 'tea', label: '茶饮', products }] }]
    }
  ];
}

function product(id, name) {
  return { id, name, price: 1290, originalPrice: 1290, storedValuePrice: 0, image: id + '.jpg' };
}

function createPage() {
  return Object.assign({}, capturedPage, {
    data: JSON.parse(JSON.stringify(capturedPage.data)),
    setData(updates) {
      Object.assign(this.data, updates);
    },
    refreshThenSync() {
      return Promise.resolve();
    },
    getTabBar() {
      return { setData() {} };
    }
  });
}

// ---- 1) 一次性交接：消费即清空，重复进入不重放 ----
const page = createPage();
assert.equal(page.consumeRecommendationCart(), null, '没有交接时必须返回 null');
app.globalData.pendingRecommendationCart = {
  reportId: 'r1',
  storeId: 'store-a',
  items: [{ id: 'tea-1', productId: 'tea-1', quantity: 1 }],
  missing: []
};
const consumed = page.consumeRecommendationCart();
assert.ok(consumed && consumed.items.length === 1, '必须消费独立门店页交来的购物车');
assert.equal(app.globalData.pendingRecommendationCart, null, '交接数据消费后必须立即清空');
assert.equal(page.consumeRecommendationCart(), null, '重复进入不得重放交接');

// ---- 2) 全有售：合并购物车并提示成功 ----
const allPage = createPage();
allPage.data.currentStore = { id: 'store-a' };
const allMenu = menuWith([product('tea-1', '滇绿'), product('tea-2', '葛根枳椇茶')]);
productListing.setMenuCatalogForTest(allMenu);
const allBuilt = recommendation.buildRecommendationCartItems(allMenu, [
  { productId: 'tea-1', name: '滇绿' },
  { productId: 'tea-2', name: '葛根枳椇茶' }
]);
toasts.length = 0;
allPage.applyRecommendationCart({ items: allBuilt.items, missing: [] });
assert.equal(allPage.data.cartCount, 2, '两件推荐商品都必须进入购物车');
assert.equal(
  allPage.data.cartItems.every(item => item.selected && item.listed),
  true
);
assert.ok(toasts.includes('已加入购物车'), '全有售必须提示已加入购物车');

// ---- 3) 旧下架条目不删除、只取消勾选，避免阻塞结算 ----
const legacyPage = createPage();
legacyPage.data.currentStore = { id: 'store-a' };
legacyPage.data.cartItems = [
  { id: 'old-line', productId: 'old-product', name: '旧商品', price: 9.9, quantity: 1, selected: true, listed: true }
];
const oneMenu = menuWith([product('tea-1', '滇绿')]);
productListing.setMenuCatalogForTest(oneMenu);
const oneBuilt = recommendation.buildRecommendationCartItems(oneMenu, [{ productId: 'tea-1', name: '滇绿' }]);
legacyPage.applyRecommendationCart({ items: oneBuilt.items, missing: [] });
const legacy = legacyPage.data.cartItems.find(item => item.productId === 'old-product');
assert.ok(legacy, '旧购物车条目不得删除');
assert.equal(legacy.listed, false);
assert.equal(legacy.selected, false, '旧下架条目必须取消勾选');
assert.equal(legacyPage.data.cartCount, 1, '结算数量只统计仍可售且勾选的商品');

// ---- 4) 部分缺货：有售加入，缺货提示已下架 ----
const partialPage = createPage();
partialPage.data.currentStore = { id: 'store-a' };
partialPage.data.cartItems = [];
productListing.setMenuCatalogForTest(oneMenu);
toasts.length = 0;
partialPage.applyRecommendationCart({
  items: oneBuilt.items,
  missing: [{ productId: 'tea-2', name: '葛根枳椇茶' }]
});
assert.ok(
  toasts.some(title => title.includes('葛根枳椇茶已下架') && title.includes('其余已加入')),
  '部分缺货必须提示已下架并说明其余已加入'
);

// ---- 5) 源码：点单页只保留消费入口，不再残留推荐模式 ----
const menuJs = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8');
assert.ok(
  menuJs.includes('consumeRecommendationCart') && menuJs.includes('applyRecommendationCart'),
  '点单页必须提供交接消费方法'
);
assert.ok(menuJs.includes('pendingRecommendationCart'), '点单页必须消费 pendingRecommendationCart');
assert.ok(!menuJs.includes('recommendationMode'), '点单页不得保留旧推荐模式');
assert.ok(
  !menuJs.includes('startRecommendationPicker') && !menuJs.includes('probeStoreAvailability'),
  '点单页不得再做推荐探测'
);
assert.ok(!menuWxml.includes('store-page__recommendation'), '点单门店层不得保留推荐提示条');
assert.ok(!menuWxml.includes('recommendationBadge'), '点单门店层不得保留推荐徽标');

console.log('menu-recommendation-flow.test.mjs: passed (pending cart consumption)');
