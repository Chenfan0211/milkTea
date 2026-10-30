import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

const toasts = [];
const switchTabs = [];
const redirects = [];
const modals = [];
let modalConfirm = true;
const backCalls = [];
const storage = new Map();
const app = { globalData: {} };
let capturedPage = null;
globalThis.Page = options => {
  capturedPage = options;
};
globalThis.getApp = () => app;
globalThis.getCurrentPages = () => [{}, {}];
globalThis.wx = {
  showToast(options) {
    toasts.push(options.title);
  },
  showLoading() {},
  hideLoading() {},
  switchTab(options) {
    switchTabs.push(options.url);
  },
  redirectTo(options) {
    redirects.push(options.url);
  },
  showModal(options) {
    modals.push(options);
    if (typeof options.success === 'function') options.success({ confirm: modalConfirm, cancel: !modalConfirm });
  },
  navigateTo() {},
  navigateBack(options) {
    backCalls.push(options || { delta: 1 });
  },
  openLocation() {},
  makePhoneCall() {},
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  },
  removeStorageSync(key) {
    storage.delete(key);
  },
  getSystemInfoSync() {
    return { platform: 'devtools' };
  }
};

const pagePath = path.join(root, 'pages/recommendation-stores/recommendation-stores.js');
require(pagePath);
const api = require(path.join(root, 'utils/api.js'));
const userProfile = require(path.join(root, 'utils/user-profile.js'));
let memberLevelFetchCount = 0;
api.fetchMemberLevels = () => {
  memberLevelFetchCount += 1;
  return Promise.resolve([{ levelCode: 'Lv1', name: '时光卡', amountTarget: 0, discount: '80' }]);
};
userProfile.saveUserProfile({ vipLevel: 'Lv1' });
const location = require(path.join(root, 'utils/location.js'));
const storeUtil = require(path.join(root, 'utils/store.js'));
const share = require(path.join(root, 'utils/share.js'));

storeUtil.setCityCatalogForTest([{ id: 1, code: '4301', name: '长沙市', latitude: 28.2282, longitude: 112.9388 }]);
const STORE_FIXTURES = [
  {
    id: 101,
    code: 's-2of2',
    name: '两件都有店',
    cityCode: '4301',
    businessStatus: 'open',
    address: 'A',
    latitude: 28.24,
    longitude: 113.08,
    modes: ['pickup']
  },
  {
    id: 102,
    code: 's-1of2',
    name: '只有一件店',
    cityCode: '4301',
    businessStatus: 'open',
    address: 'B',
    latitude: 28.25,
    longitude: 113.09,
    modes: ['pickup']
  },
  {
    id: 103,
    code: 's-1of2-near',
    name: '只有一件近店',
    cityCode: '4301',
    businessStatus: 'open',
    address: 'C',
    latitude: 28.241,
    longitude: 113.081,
    modes: ['pickup']
  },
  {
    id: 104,
    code: 's-0of2',
    name: '全缺货店',
    cityCode: '4301',
    businessStatus: 'open',
    address: 'D',
    latitude: 28.239,
    longitude: 113.079,
    modes: ['pickup']
  },
  {
    id: 105,
    code: 's-unprobed',
    name: '远处未探测店',
    cityCode: '4301',
    businessStatus: 'open',
    address: 'E',
    latitude: 28.3,
    longitude: 113.15,
    modes: ['pickup']
  },
  {
    id: 106,
    code: 's-far-2of2',
    name: '远处两件都有店',
    cityCode: '4301',
    businessStatus: 'open',
    address: 'F',
    latitude: 28.32,
    longitude: 113.18,
    modes: ['pickup']
  }
];
storeUtil.setStoreCatalogForTest(STORE_FIXTURES);

function product(id, name) {
  return { id, name, price: 1290, originalPrice: 1290, storedValuePrice: 0, image: id + '.jpg' };
}
function menuWith(products) {
  return [
    {
      id: 'menu',
      label: '点单',
      groups: [{ id: 'all', label: '全部', categories: [{ id: 'tea', label: '茶饮', products }] }]
    }
  ];
}
const DISTANCES = {
  's-2of2': 4.5,
  's-1of2': 4.8,
  's-1of2-near': 0.8,
  's-0of2': 0.5,
  's-unprobed': 25.5,
  's-far-2of2': 30.0
};
const MENUS = {
  101: menuWith([product('p1', '商品一'), product('p2', '商品二')]),
  102: menuWith([product('p1', '商品一')]),
  103: menuWith([product('p1', '商品一')]),
  104: menuWith([]),
  105: menuWith([product('p1', '商品一'), product('p2', '商品二')]),
  106: menuWith([product('p1', '商品一'), product('p2', '商品二')])
};

api.fetchCities = () => Promise.resolve([]);
api.fetchStores = () => Promise.resolve(STORE_FIXTURES);
api.fetchStoreDistances = (latitude, longitude, stores) =>
  Promise.resolve(
    (stores || []).map((store, index) => ({
      index,
      distanceKm: DISTANCES[store.id],
      durationMinutes: Math.round(DISTANCES[store.id] * 2)
    }))
  );
api.fetchMenu = subjectId => Promise.resolve(MENUS[subjectId] || []);
location.locate = () =>
  Promise.resolve({
    source: 'device',
    latitude: 28.24,
    longitude: 113.08,
    cityCode: '4301',
    cityName: '长沙市',
    granted: true
  });

function createPage() {
  return Object.assign({}, capturedPage, {
    data: JSON.parse(JSON.stringify(capturedPage.data)),
    setData(updates) {
      Object.assign(this.data, updates);
    }
  });
}

async function waitFor(label, fn, timeout = 15000) {
  const startedAt = Date.now();
  let last = null;
  while (Date.now() - startedAt < timeout) {
    last = await fn();
    if (last) return last;
    await sleep(50);
  }
  throw new Error('waitFor timeout: ' + label + ' last=' + JSON.stringify(last));
}

// ---- 1) 正常链路：定位 -> 可售探测 -> 数量优先排序 ----
app.globalData.pendingRecommendationAdd = {
  reportId: 'r1',
  items: [
    { productId: 'p1', name: '商品一' },
    { productId: 'p2', name: '商品二' }
  ]
};
const page = createPage();
page.onLoad();
await waitFor('page loaded', () => !page.data.loading && page.data.priorityStores.length);
assert.deepEqual(
  page.data.priorityStores.map(store => store.id),
  ['s-2of2', 's-1of2-near', 's-1of2'],
  '优先组必须按可售数量降序，同数量按距离升序'
);
assert.deepEqual(
  page.data.otherStores.map(store => store.id),
  ['s-0of2', 's-unprobed', 's-far-2of2'],
  '0 可售与 5 公里外必须统一按距离升序'
);
assert.equal(page.data.priorityStores[0].recommendationBadge.text, '推荐商品 2/2 有售');
assert.equal(page.data.priorityStores[1].recommendationBadge.text, '推荐商品 1/2 有售');
assert.equal(page.data.otherStores[0].recommendationBadge.text, '推荐商品 0/2');
assert.equal(page.data.hint.includes('5 公里'), true);
assert.equal(page.data.markers.length, 6, '地图 marker 必须覆盖全部门店');
assert.equal(app.globalData.pendingRecommendationAdd, null, '进入门店页后必须清空报告页交接');

// ---- 2) 搜索只过滤、不改变组内顺序 ----
page.handleSearchInput({ detail: { value: '只有' } });
assert.deepEqual(
  page.data.priorityStores.map(store => store.id),
  ['s-1of2-near', 's-1of2']
);
assert.deepEqual(page.data.otherStores, []);
page.clearSearch();
assert.equal(page.data.priorityStores.length + page.data.otherStores.length, 6);

// ---- 3) 返回上一级 ----
page.handleBack();
assert.equal(backCalls.length, 1, '门店页返回必须调用 navigateBack');
assert.deepEqual(backCalls[0], { delta: 1 });

// ---- 4) 全部缺货：保持当前页、不写交接 ----
toasts.length = 0;
switchTabs.length = 0;
await page.handleSelectStore({ detail: { store: page.data.otherStores.find(store => store.id === 's-0of2') } });
assert.equal(app.globalData.pendingRecommendationCart, undefined, '全部缺货不得写购物车交接');
assert.equal(switchTabs.length, 0, '全部缺货不得跳点单页');
assert.ok(
  toasts.some(title => title.includes('均已下架')),
  '全部缺货必须提示换店'
);

// ---- 5) 优先门店选店：写交接、持久化门店并切点单页 ----
toasts.length = 0;
switchTabs.length = 0;
await page.handleSelectStore({ detail: { store: page.data.priorityStores[0] } });
await waitFor('pending cart', () => app.globalData.pendingRecommendationCart);
const pending = app.globalData.pendingRecommendationCart;
assert.equal(pending.storeId, 's-2of2');
assert.equal(pending.items.length, 2, '两件有售商品都必须交接给点单页');
assert.ok(memberLevelFetchCount > 0, '选店页必须刷新会员等级后再构建购物车价格');
assert.equal(pending.items[0].price, 10.32, '一键加购必须使用点单页会员价口径');
assert.equal(pending.items[0].originalPrice, 12.9, '一键加购必须保留点单页原价口径');
assert.equal(switchTabs.indexOf('/pages/menu/menu') >= 0, true, '选店成功必须切点单页');
const preference = storage.get('milkTea:store-preference');
assert.ok(preference && preference.activeStoreId === 's-2of2', '选店必须持久化当前门店');

// ---- 6) 无交接数据：页面给出失效提示而不是空白 ----
app.globalData.pendingRecommendationAdd = null;
const emptyPage = createPage();
emptyPage.onLoad();
assert.equal(emptyPage.data.loading, false);
assert.ok(emptyPage.data.error.includes('推荐商品已失效'), '直接进入无交接时必须提示重新发起');

// ---- 8) 立即购买：选店成功直接进入确认订单页 ----
app.globalData.pendingOrder = null;
redirects.length = 0;
app.globalData.pendingRecommendationAdd = {
  intent: 'buyNow',
  reportId: 'r-buy',
  items: [{ productId: 'p1', name: '商品一', selectedOptionIds: ['small'], quantity: 2, cartItemId: '' }]
};
const buyPage = createPage();
buyPage.onLoad();
await waitFor('buy page loaded', () => !buyPage.data.loading && buyPage.data.priorityStores.length);
await buyPage.handleSelectStore({
  detail: { store: buyPage.data.priorityStores.find(store => store.id === 's-2of2') }
});
assert.equal(app.globalData.pendingOrder.items.length, 1);
assert.equal(app.globalData.pendingOrder.items[0].quantity, 2, '立即购买必须保留数量');
assert.equal(app.globalData.pendingOrder.fromCart, false);
assert.equal(app.globalData.pendingOrder.cartScope, undefined);
assert.ok(
  redirects.some(url => url.includes('/pages/order-confirm/order-confirm')),
  '立即购买选店后必须进入结算页'
);

// ---- 9) 详情购物车部分缺货：确认后仅将有售条目带入结算 ----
app.globalData.pendingOrder = null;
redirects.length = 0;
modals.length = 0;
modalConfirm = true;
app.globalData.pendingRecommendationAdd = {
  reportId: 'r-cart',
  intent: 'cartCheckout',
  cartScope: 'recommendation',
  items: [
    { productId: 'p1', name: '商品一', selectedOptionIds: ['small'], quantity: 1, cartItemId: 'cart-p1' },
    { productId: 'p2', name: '商品二', selectedOptionIds: ['small'], quantity: 1, cartItemId: 'cart-p2' }
  ]
};
const partialPage = createPage();
partialPage.onLoad();
await waitFor('partial page loaded', () => !partialPage.data.loading && partialPage.data.priorityStores.length);
await partialPage.handleSelectStore({
  detail: { store: partialPage.data.priorityStores.find(store => store.id === 's-1of2-near') }
});
assert.equal(modals.length, 1, '部分缺货必须弹窗一次');
assert.ok(modals[0].content.includes('商品二'), '弹窗必须列出缺货商品');
assert.equal(app.globalData.pendingOrder.items.length, 1, '确认后只结算有售商品');
assert.equal(app.globalData.pendingOrder.items[0].cartItemId, 'cart-p1');
assert.equal(app.globalData.pendingOrder.fromCart, true);
assert.equal(app.globalData.pendingOrder.cartScope, 'recommendation');
assert.equal(app.globalData.pendingOrder.reportId, 'r-cart', '推荐购物车结算必须保留 reportId');

// ---- 10) 部分缺货取消：停留选店页，不进入结算 ----
app.globalData.pendingOrder = null;
redirects.length = 0;
modalConfirm = false;
app.globalData.pendingRecommendationAdd = {
  intent: 'cartCheckout',
  cartScope: 'recommendation',
  items: [
    { productId: 'p1', name: '商品一', selectedOptionIds: ['small'], quantity: 1, cartItemId: 'cart-p1' },
    { productId: 'p2', name: '商品二', selectedOptionIds: ['small'], quantity: 1, cartItemId: 'cart-p2' }
  ]
};
const cancelPage = createPage();
cancelPage.onLoad();
await waitFor('cancel page loaded', () => !cancelPage.data.loading && cancelPage.data.priorityStores.length);
await cancelPage.handleSelectStore({
  detail: { store: cancelPage.data.priorityStores.find(store => store.id === 's-1of2-near') }
});
assert.equal(app.globalData.pendingOrder, null, '取消部分缺货提示后不得进入结算');
assert.equal(redirects.length, 0, '取消部分缺货提示后不得跳结算页');
modalConfirm = true;

// ---- 11) 全部缺货：提示并停留，不进入结算 ----
app.globalData.pendingOrder = null;
redirects.length = 0;
toasts.length = 0;
app.globalData.pendingRecommendationAdd = {
  intent: 'buyNow',
  items: [{ productId: 'p1', name: '商品一', selectedOptionIds: ['small'], quantity: 1, cartItemId: '' }]
};
const missingPage = createPage();
missingPage.onLoad();
await waitFor('missing page loaded', () => !missingPage.data.loading && missingPage.data.otherStores.length);
await missingPage.handleSelectStore({
  detail: { store: missingPage.data.otherStores.find(store => store.id === 's-0of2') }
});
assert.equal(app.globalData.pendingOrder, null, '全部缺货不得进入结算');
assert.equal(redirects.length, 0, '全部缺货不得跳结算页');
assert.ok(
  toasts.some(title => title.includes('均已下架')),
  '全部缺货必须提示换店'
);
// ---- 7) 结构：主包 webview 地图页、私有分享、返回/分组/徽标 ----
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/recommendation-stores/recommendation-stores'), '门店页必须注册到主包');
const pageJson = JSON.parse(
  fs.readFileSync(path.join(root, 'pages/recommendation-stores/recommendation-stores.json'), 'utf8')
);
assert.equal(pageJson.renderer, 'webview', '门店页必须用 webview 渲染地图');
assert.equal(
  pageJson.usingComponents['navigation-bar'],
  '/components/navigation-bar/navigation-bar',
  '门店页必须注册标准 navigation-bar'
);
const pageWxml = fs.readFileSync(path.join(root, 'pages/recommendation-stores/recommendation-stores.wxml'), 'utf8');
const pageJs = fs.readFileSync(pagePath, 'utf8');
assert.ok(pageWxml.includes('<map') && pageWxml.includes('bindmarkertap="handleMarkerTap"'), '门店页必须内嵌地图');
assert.ok(
  pageWxml.includes('back="{{true}}"') &&
    pageWxml.includes('delta="{{0}}"') &&
    pageWxml.includes('bind:back="handleBack"'),
  '门店页必须使用 navigation-bar 返回并交由页面处理'
);
assert.ok(
  !pageWxml.includes('cover-view class="recommendation-stores__nav"'),
  '门店页不得再使用易失效的 cover-view 返回'
);
assert.ok(
  pageWxml.includes('handleSearchInput') && pageWxml.includes('selectCity') && pageWxml.includes('handleLocate'),
  '门店页必须支持搜索/城市/重新定位'
);
assert.ok(pageWxml.includes('优先推荐') && pageWxml.includes('其他门店'), '门店页必须按优先级分组展示');
assert.ok(pageWxml.includes('5 公里外或暂无推荐商品'), '其他门店必须说明 5 公里外/0 可售的排序口径');
assert.ok(pageWxml.includes('availability="{{item.recommendationBadge}}"'), '门店卡必须展示可售数量徽标');
assert.ok(
  pageJs.includes('annotateAndSortStores') && pageJs.includes('pendingRecommendationCart'),
  '门店页必须复用纯函数排序并写点单交接'
);
assert.ok(share.isPrivatePage('pages/recommendation-stores/recommendation-stores'), '门店页必须为私密分享页');

console.log('recommendation-stores-page.test.mjs: passed');
