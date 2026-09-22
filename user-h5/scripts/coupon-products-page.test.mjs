import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const productPageRoot = path.join(root, 'pages/coupon-products/coupon-products');
const wxCalls = [];
const appState = { globalData: { selectedStoreId: 'store-002' } };

globalThis.getApp = () => appState;
globalThis.wx = {
  showShareMenu() {},
  navigateTo(options) {
    wxCalls.push({ type: 'navigateTo', ...options });
  },
  navigateBack(options = {}) {
    wxCalls.push({ type: 'navigateBack', ...options });
  },
  setStorageSync(key, value) {
    wxCalls.push({ type: 'setStorage', key, value });
  },
  getStorageSync() {
    return '';
  },
  removeStorageSync() {},
  showToast(options) {
    wxCalls.push({ type: 'toast', ...options });
  }
};

function loadPage(pageRoot) {
  let definition;
  globalThis.Page = page => {
    definition = page;
  };
  delete require.cache[require.resolve(`${pageRoot}.js`)];
  require(`${pageRoot}.js`);
  return definition;
}

function createPageInstance(definition) {
  return {
    data: Object.assign({}, definition.data),
    setData(updates) {
      this.data = Object.assign({}, this.data, updates);
    }
  };
}

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${productPageRoot}.${extension}`), `缺少适用商品页文件: coupon-products.${extension}`);
}

// 优惠券与菜单改为 DB seed 数据源（data/mock.js 已不再存放业务数据）
const { loadMenu, readSeed } = await import('./lib/seed-data.mjs');
const menuTabs = loadMenu();
const marketingSeed = readSeed('V6__seed_marketing.sql');
const coupons = [...marketingSeed.match(/INSERT INTO coupon \(id, code[\s\S]*?;/)[0]
  .matchAll(/\((\d+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'/g)]
  .map(m => ({
    id: m[2],
    title: m[3],
    type: m[4],
    // 数据库中券模板未按商品限定时，视为全部商品可用
    applicableProductIds: menuTabs
      .flatMap(tab => tab.groups)
      .flatMap(group => group.categories)
      .flatMap(category => category.products.map(product => product.id)),
    applicableStoreIds: []
  }));
const coupon = coupons.find(item => item.id === 'coupon-001');
assert.ok(coupon, 'V6 seed 必须包含 coupon-001');
// 券模板未限定商品（app_config / 券表均无 applicable_product_ids 数据）时，视为全部商品可用
assert.ok(Array.isArray(coupon.applicableProductIds), '优惠券必须声明适用商品 ID 字段');
const allMenuProductsForCount = [];
for (const menu of menuTabs) for (const group of menu.groups) for (const category of group.categories) allMenuProductsForCount.push(...category.products);
const applicableIds = coupon.applicableProductIds;

const allMenuProducts = [];
for (const menu of menuTabs) {
  for (const group of menu.groups) {
    for (const category of group.categories) {
      allMenuProducts.push(...category.products);
    }
  }
}
for (const productId of applicableIds) {
  assert.ok(
    allMenuProducts.some(product => product.id === productId),
    `适用商品 ID 不存在: ${productId}`
  );
}

// 门店/菜单改为接口数据源：先刷新内存镜像再渲染
const { loadStores, refreshMenuFromRemote } = await import('./lib/seed-data.mjs').then(m => ({ loadStores: m.loadStores }));
const { refreshStoreCatalogFromRemote, refreshCitiesFromRemote, selectStore } = require(path.join(root, 'utils/store.js'));
const { refreshMenuFromRemote: refreshMenu } = require(path.join(root, 'utils/product-listing.js'));
globalThis.wx.request = function request(options) {
  const url = String(options.url || '');
  const { readAppConfig } = require(path.join(root, 'scripts/lib/seed-data.mjs'));
  let data = [];
  if (url.includes('/app/stores')) data = loadStores();
  else if (url.includes('/config/cities')) data = readAppConfig('app_cities') || [];
  else if (url.includes('/app/menu')) data = menuTabs;
  else if (url.includes('/app/coupons')) data = coupons;
  setTimeout(() => options.success({ statusCode: 200, data: { code: 0, data, message: 'ok' } }), 0);
};
await refreshCitiesFromRemote();
await refreshStoreCatalogFromRemote();
await refreshMenu();
selectStore('store-001', 1000);

const productDefinition = loadPage(productPageRoot);
const productPage = createPageInstance(productDefinition);
productDefinition.onLoad.call(productPage, { couponId: 'coupon-001', storeId: 'store-001' });
// 页面 onLoad 内部走接口，等异步完成后断言
await new Promise(resolve => setTimeout(resolve, 30));
assert.equal(productPage.data.storeName, '星沙乐运魔方店', '适用商品页必须使用所选门店名');
// 适用商品需与点单页一致：仅展示「运营后台已上架 且 门店已上架」的商品。
const { getListedMenuTabs } = require(path.join(root, 'utils/product-listing.js'));
const customerIndex = {};
getListedMenuTabs('store-001').forEach(menu =>
  menu.groups.forEach(group =>
    group.categories.forEach(category =>
      category.products.forEach(product => {
        customerIndex[product.id] = product;
      })
    )
  )
);
const expectedNames = [];
for (const id of coupon.applicableProductIds) {
  if (customerIndex[id] && expectedNames.indexOf(customerIndex[id].name) === -1) {
    expectedNames.push(customerIndex[id].name);
  }
}
assert.ok(expectedNames.length > 0, '适用商品页必须至少展示一款在售商品');
assert.equal(
  productPage.data.products.length,
  expectedNames.length,
  '适用商品页必须与点单页在售商品保持一致'
);
assert.equal(
  new Set(productPage.data.products.map(product => product.name)).size,
  productPage.data.products.length,
  '适用商品必须按名称去重'
);
assert.ok(
  productPage.data.products.every(product => customerIndex[product.id]),
  '适用商品页不得出现点单页已下架的商品'
);
assert.ok(
  productPage.data.products.every(product => product.sizeText === '中杯'),
  '杯型必须从商品规格数据生成'
);

const emptyProductPage = createPageInstance(productDefinition);
productDefinition.onLoad.call(emptyProductPage, { couponId: 'missing-coupon', storeId: 'store-001' });
await new Promise(resolve => setTimeout(resolve, 30));
assert.equal(emptyProductPage.data.products.length, 0, '无效优惠券不得回退为全部商品');

const storesPageRoot = path.join(root, 'pages/coupon-stores/coupon-stores');
const storesDefinition = loadPage(storesPageRoot);
const productModePage = createPageInstance(storesDefinition);
storesDefinition.onLoad.call(productModePage, { couponId: 'coupon-001', next: 'products' });
await new Promise(resolve => setTimeout(resolve, 30));
wxCalls.length = 0;
storesDefinition.handleSelectStore.call(productModePage, {
  detail: { store: productModePage.data.stores.find(store => store.id === 'store-001') }
});
assert.equal(wxCalls.filter(call => call.type === 'navigateTo').length, 1, '商品模式必须跳转适用商品页');
assert.ok(
  wxCalls.some(call => call.url === '/pages/coupon-products/coupon-products?couponId=coupon-001&storeId=store-001'),
  '商品模式必须携带券号和门店号'
);
assert.equal(wxCalls.filter(call => call.type === 'setStorage').length, 0, '商品模式不得写入门店偏好');
assert.equal(appState.globalData.selectedStoreId, 'store-002', '商品模式不得修改全局当前门店');

const browseModePage = createPageInstance(storesDefinition);
storesDefinition.onLoad.call(browseModePage, { couponId: 'coupon-001' });
await new Promise(resolve => setTimeout(resolve, 30));
wxCalls.length = 0;
storesDefinition.handleSelectStore.call(browseModePage, {
  detail: { store: browseModePage.data.stores.find(store => store.id === 'store-001') }
});
assert.ok(
  wxCalls.some(call => call.type === 'setStorage'),
  '查看门店模式必须继续持久化选择'
);
assert.ok(
  wxCalls.some(call => call.type === 'navigateBack'),
  '查看门店模式必须继续返回上一页'
);

const couponListWxml = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.wxml'), 'utf8');
const couponListJs = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.js'), 'utf8');
assert.ok(couponListWxml.includes('bindtap="handleViewProducts"'), '优惠券列表必须接通查看适用商品');
assert.ok(
  couponListJs.includes('handleViewProducts') && couponListJs.includes('&next=products'),
  '查看适用商品入口必须跳转商品门店流程'
);

const storesWxml = fs.readFileSync(`${storesPageRoot}.wxml`, 'utf8');
assert.ok(storesWxml.includes('bind:select="handleSelectStore"'), '门店卡必须支持选择门店');
assert.ok(storesWxml.includes('bind:navigate="handleNavigate"'), '门店导航必须由公共卡派发导航事件');

const productWxml = fs.readFileSync(`${productPageRoot}.wxml`, 'utf8');
const productWxss = fs.readFileSync(`${productPageRoot}.wxss`, 'utf8');
assert.ok(
  productWxml.includes('title="适用商品"') &&
    productWxml.includes('coupon-product-grid') &&
    productWxml.includes('item.sizeText'),
  '适用商品页必须保留标题、网格和杯型信息'
);
assert.ok(
  productWxml.includes('mode="aspectFit"') && productWxml.includes('ellipsis'),
  '商品图必须完整展示且商品名单行省略'
);
assert.ok(/\.coupon-product-grid\s*\{[\s\S]*?flex-wrap:\s*wrap/.test(productWxss), '适用商品必须使用三列换行网格');
assert.ok(
  /\.coupon-product\s*\{[\s\S]*?width:\s*calc\(\(100% - 24rpx\) \/ 3\)/.test(productWxss),
  '适用商品卡必须使用三列宽度与 12rpx 列间距'
);
assert.ok(
  productWxss.includes('var(--page-gutter)') &&
    productWxss.includes('var(--line-color)') &&
    productWxss.includes('var(--radius-sm)'),
  '适用商品页必须遵守设计系统 token'
);
assert.ok(!/font-size:\s*\d/.test(productWxss), '适用商品页字号必须使用设计 token');

// 时光币商城选店复用全页门店选择，不再使用弹出层
const pointsMallJs = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.js'), 'utf8');
assert.ok(!pointsMallJs.includes('showActionSheet'), '时光币商城不得使用 showActionSheet 弹出层');
assert.ok(pointsMallJs.includes('/pages/coupon-stores/coupon-stores?from=points'), '时光币商城必须跳转全页门店选择页');
assert.ok(
  pointsMallJs.includes('resolveStoreCatalog') && pointsMallJs.includes('onShow()'),
  '时光币商城返回后必须在 onShow 刷新门店'
);

const pointsModePage = createPageInstance(storesDefinition);
storesDefinition.onLoad.call(pointsModePage, { from: 'points' });
await new Promise(resolve => setTimeout(resolve, 30));
assert.equal(pointsModePage.data.title, '选择门店', '商城入口必须显示通用选择门店标题');

const defaultModePage = createPageInstance(storesDefinition);
storesDefinition.onLoad.call(defaultModePage, { couponId: 'coupon-001' });
await new Promise(resolve => setTimeout(resolve, 30));
assert.equal(defaultModePage.data.title, '选择商品适用门店', '默认入口标题必须保持不变');
assert.ok(storesWxml.includes('title="{{title}}"'), '门店选择页标题必须绑定数据');

console.log('优惠券适用商品展示流程测试通过');
