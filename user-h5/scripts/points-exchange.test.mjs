import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = {};
const toasts = [];
const redirects = [];
let modalOptions = null;
let navigateBackCount = 0;

globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    toasts.push(options);
  },
  showModal(options) {
    modalOptions = options;
  },
  navigateBack() {
    navigateBackCount += 1;
  },
  navigateTo(options) {
    redirects.push(options);
  },
  redirectTo(options) {
    redirects.push(options);
  },
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 })
};
const mockApp = {
  globalData: {
    points: 1000,
    pointsRecords: [],
    exchangeRecords: [],
    exchangeVerifyPool: []
  }
};
globalThis.getApp = () => mockApp;

const api = require(path.join(root, 'utils/api.js'));
const { getPoints, setPoints, verifyExchange } = require(path.join(root, 'utils/points.js'));

function delay(ms = 20) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function loadPage(relativePath) {
  const absolutePath = path.join(root, relativePath);
  delete require.cache[require.resolve(absolutePath)];
  let definition;
  globalThis.Page = page => {
    definition = page;
  };
  require(absolutePath);
  return definition;
}

function createPageInstance(definition, route) {
  const instance = {
    route,
    data: Object.assign({}, definition.data),
    setData(updates, callback) {
      this.data = Object.assign({}, this.data, updates);
      if (typeof callback === 'function') callback();
    }
  };
  Object.keys(definition).forEach(key => {
    if (typeof definition[key] === 'function') {
      instance[key] = definition[key].bind(instance);
    }
  });
  return instance;
}

function stubApi(name, implementation) {
  api[name] = implementation;
}

function restoreApi(name, implementation) {
  api[name] = implementation;
}

// 假数据清理：兑换与核销均为服务端写操作，前端不再有本地状态机。
// utils/points.js 只保留余额读写（缓存镜像）与核销接口封装。
assert.equal(
  typeof require(path.join(root, 'utils/points.js')).exchangeProduct,
  'undefined',
  'exchangeProduct must be removed (exchange is a server-side write now)'
);

// 余额读写：以 profile 为持久源
setPoints(60);
assert.equal(getPoints(), 60, 'setPoints/getPoints must round-trip the balance');
setPoints(-5);
assert.equal(getPoints(), 0, 'setPoints must clamp negatives to zero');
setPoints(1000);

// 核销：走后端 POST /api/v1/app/gift-cards/verify（按订单号）
const apiSource = fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8');
assert.ok(
  apiSource.includes("'/api/v1/app/gift-cards/verify'"),
  'api.js must expose the gift-card verify endpoint'
);
assert.ok(
  apiSource.includes('verifyGiftCardOrder'),
  'api.js must export verifyGiftCardOrder'
);
assert.match(apiSource, /function fetchPointsCategories\s*\(/, 'api.js must expose points categories');
assert.match(apiSource, /function fetchPointsProducts\s*\(\s*category\s*\)/, 'fetchPointsProducts must accept category');
assert.match(apiSource, /function exchangePointsProduct\s*\(\s*productId\s*,\s*quantity/, 'exchange must accept quantity');
assert.match(
  apiSource,
  /data:\s*\{\s*productId\s*,\s*quantity\s*\}/,
  'exchange must send JSON productId and quantity'
);

const pointsSource = fs.readFileSync(path.join(root, 'utils/points.js'), 'utf8');
assert.ok(
  !pointsSource.includes('milkTea:exchange:pool'),
  'verify must not use the local exchange pool anymore'
);
assert.ok(
  pointsSource.includes('verifyGiftCardOrder'),
  'verifyExchange must delegate to the backend verify endpoint'
);

// 空码直接短路，不发起请求
const emptyResult = await verifyExchange('');
assert.equal(emptyResult.ok, false, 'empty code must not verify');
assert.equal(emptyResult.reason, 'empty', 'empty code reason must be "empty"');

// 核销结果映射：后端业务错误 -> 可展示原因
const originalVerify = api.verifyGiftCardOrder;
stubApi('verifyGiftCardOrder', () => Promise.resolve({ orderNo: 'EX123' }));
const okResult = await verifyExchange('EX123');
assert.equal(okResult.ok, true, 'successful verify must resolve ok');
assert.equal(okResult.order.orderNo, 'EX123', 'verify must return the backend order');

stubApi('verifyGiftCardOrder', () => Promise.reject(new Error('订单已核销')));
const dupResult = await verifyExchange('EX123');
assert.equal(dupResult.ok, false, 'duplicate verify must fail');
assert.equal(dupResult.reason, 'already_verified', 'duplicate reason must be already_verified');

stubApi('verifyGiftCardOrder', () => Promise.reject(new Error('礼品卡订单不存在')));
const missingResult = await verifyExchange('NOPE');
assert.equal(missingResult.ok, false, 'unknown code must fail');
assert.equal(missingResult.reason, 'not_found', 'unknown code reason must be not_found');

stubApi('verifyGiftCardOrder', () => Promise.reject(new Error('订单未支付，无法核销')));
const unpaidResult = await verifyExchange('EX999');
assert.equal(unpaidResult.ok, false, 'unpaid order verify must fail');
assert.equal(unpaidResult.reason, 'failed', 'unpaid reason must fall back to failed');
restoreApi('verifyGiftCardOrder', originalVerify);

// 积分商城：分类必须来自接口，商品只拉取一次后在客户端按当前分类筛选。
const pointsMallJs = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.js'), 'utf8');
const pointsMallWxml = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.wxml'), 'utf8');
assert.match(pointsMallJs, /fetchPointsCategories/, '积分商城必须加载分类接口');
assert.match(pointsMallJs, /fetchPointsProducts/, '积分商城必须加载商品接口');
assert.match(pointsMallJs, /Promise\.all/, '分类与商品必须并行加载');
assert.doesNotMatch(pointsMallJs, /data\/mock/, '积分商城不得依赖 mock 分类回退');
assert.match(pointsMallWxml, /pointsCategories/, '积分商城页签必须渲染接口分类');
assert.match(pointsMallWxml, /empty-state/, '分类或商品为空时必须展示空状态');
assert.doesNotMatch(
  fs.readFileSync(path.join(root, 'data/mock.js'), 'utf8'),
  /pointsCategories/,
  '积分商城分类不得继续保留在 mock.js 中'
);

const mallDefinition = loadPage('pages/points-mall/points-mall');
assert.deepEqual(
  mallDefinition.data.pointsCategories,
  [{ id: 'all', label: '全部' }],
  '积分商城初始化只能包含虚拟 all 页签'
);
const originalCategories = api.fetchPointsCategories;
const originalProducts = api.fetchPointsProducts;
const mallProductCalls = [];
stubApi('fetchPointsCategories', () => Promise.resolve([
  { code: 'coupon', name: '优惠券区', sort: 1, enabled: 1 },
  { code: 'pet', name: '宠物公益', sort: 2, enabled: 1 }
]));
stubApi('fetchPointsProducts', category => {
  mallProductCalls.push(category);
  return Promise.resolve([
    { id: 1, code: 'pet-product', name: '宠物商品', category: 'pet' },
    { id: 9, code: 'coupon-product', name: '优惠券商品', category: 'coupon' }
  ]);
});
const mallPage = createPageInstance(mallDefinition, 'pages/points-mall/points-mall');
mallPage.syncStore = mallDefinition.syncStore;
await mallDefinition.onLoad.call(mallPage, {});
await delay();
assert.deepEqual(
  mallPage.data.pointsCategories,
  [
    { id: 'all', label: '全部' },
    { id: 'coupon', label: '优惠券区' },
    { id: 'pet', label: '宠物公益' }
  ],
  '接口分类必须映射为 id/label 并保留“全部”'
);
assert.deepEqual(
  mallPage.data.filteredProducts.map(item => item.id),
  [1, 9],
  '首次加载应展示商品接口结果'
);
assert.deepEqual(mallProductCalls, [undefined], '初次只应请求全部商品');
mallDefinition.filterCategory.call(mallPage, { currentTarget: { dataset: { id: 'coupon' } } });
assert.deepEqual(mallProductCalls, [undefined], '切换分类不得重新请求商品');
assert.deepEqual(mallPage.data.filteredProducts.map(item => item.id), [9], '切换分类必须本地筛选商品');

mallDefinition.filterCategory.call(mallPage, { currentTarget: { dataset: { id: 'removed' } } });
assert.equal(mallPage.data.activeCategory, 'all', '当前分类失效时必须自动回到全部');
assert.deepEqual(mallPage.data.filteredProducts.map(item => item.id), [1, 9], '失效分类回退后必须展示全部商品');

stubApi('fetchPointsCategories', () => Promise.reject(new Error('offline')));
const failedMallPage = createPageInstance(mallDefinition, 'pages/points-mall/points-mall');
failedMallPage.syncStore = mallDefinition.syncStore;
await mallDefinition.onLoad.call(failedMallPage, {});
await delay();
assert.deepEqual(failedMallPage.data.pointsCategories.map(item => item.id), ['all'], '分类接口失败不得回退固定假分类');
assert.deepEqual(
  failedMallPage.data.filteredProducts.map(item => item.id),
  [1, 9],
  '分类接口失败时仍必须展示全部可用商品'
);
restoreApi('fetchPointsCategories', originalCategories);
restoreApi('fetchPointsProducts', originalProducts);

// 兑换详情：按后端数值 id 定位，普通商品传实际数量，优惠券固定 1 张。
const exchangeJs = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.js'), 'utf8');
const exchangeWxml = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.wxml'), 'utf8');
assert.doesNotMatch(exchangeJs, /normalizeId/, '兑换详情不得再用 code 字符串作为唯一匹配键');
assert.doesNotMatch(exchangeJs, /list\[0\]/, '兑换详情不得在匹配失败时落到首项');
assert.match(exchangeJs, /String\([^)]*\.id\)/, '兑换详情必须按后端数值 id 定位商品');
assert.match(exchangeJs, /exchangePointsProduct\(item\.id\s*,\s*quantity\)/, '兑换必须传递实际数量');
assert.match(exchangeWxml, /wx:if="\{\{!isCoupon\}\}"/, '优惠券商品必须隐藏数量步进器');

const exchangeDefinition = loadPage('pages/points-exchange/points-exchange');
const originalExchange = api.exchangePointsProduct;
const exchangeCalls = [];
stubApi('fetchPointsProducts', () => Promise.resolve([
  { id: 7, code: 'first-product', name: '第一件', points: 10, stock: 8, category: 'pet' },
  { id: 42, code: 'points-coupon-3', name: '目标商品', points: 20, stock: 5, category: 'pet', purchaseLimit: 5 }
]));
stubApi('exchangePointsProduct', (productId, quantity) => {
  exchangeCalls.push([productId, quantity]);
  return Promise.resolve({});
});
const normalExchangePage = createPageInstance(exchangeDefinition, 'pages/points-exchange/points-exchange');
await exchangeDefinition.onLoad.call(normalExchangePage, { id: '42' });
await delay();
assert.equal(normalExchangePage.data.item.id, 42, '必须按数值 id 定位目标商品');
normalExchangePage.increaseQuantity();
normalExchangePage.increaseQuantity();
await exchangeDefinition.doExchange.call(normalExchangePage);
await delay();
assert.deepEqual(exchangeCalls.at(-1), [42, 3], '普通商品必须按实际数量兑换');

stubApi('fetchPointsProducts', () => Promise.resolve([
  { id: 8, code: 'points-coupon-3', name: '3元优惠券', points: 20, stock: 5, category: 'coupon' }
]));
const couponExchangePage = createPageInstance(exchangeDefinition, 'pages/points-exchange/points-exchange');
await exchangeDefinition.onLoad.call(couponExchangePage, { id: '8' });
await delay();
assert.equal(couponExchangePage.data.isCoupon, true, '优惠券分类必须标记为优惠券商品');
assert.equal(couponExchangePage.data.maxQuantity, 1, '优惠券商品最大兑换数量必须为 1');
couponExchangePage.increaseQuantity();
assert.equal(couponExchangePage.data.quantity, 1, '优惠券商品数量不得增加');
const redirectCountBeforeCoupon = redirects.length;
await exchangeDefinition.doExchange.call(couponExchangePage);
await delay();
assert.deepEqual(exchangeCalls.at(-1), [8, 1], '优惠券商品必须固定按 1 张兑换');
assert.equal(redirects[redirects.length - 1].url, '/pages/coupon-list/coupon-list', '优惠券兑换成功必须跳转我的优惠券');
assert.ok(redirects.length > redirectCountBeforeCoupon, '优惠券兑换成功必须发生页面跳转');
restoreApi('exchangePointsProduct', originalExchange);

// 兑换记录页含待核销页签，且已完成与自提码展示由模板条件控制。
const mockSource = fs.readFileSync(path.join(root, 'data/mock.js'), 'utf8');
assert.ok(mockSource.includes("id: 'pending_verify'"), '兑换记录分类必须含待核销');
const exchangeRecordsWxml = fs.readFileSync(path.join(root, 'pages/exchange-records/exchange-records.wxml'), 'utf8');
assert.match(exchangeRecordsWxml, /wx:if="\{\{item\.pickupCode\}\}"/, '无自提码时不得显示自提码');
const exchangeRecordsDefinition = loadPage('pages/exchange-records/exchange-records');
const exchangeRecordsPage = createPageInstance(exchangeRecordsDefinition, 'pages/exchange-records/exchange-records');
exchangeRecordsPage.statusText = exchangeRecordsDefinition.statusText;
assert.equal(
  exchangeRecordsDefinition.statusText.call(exchangeRecordsPage, 'COMPLETED'),
  '已完成',
  '后端大写 COMPLETED 状态必须显示为已完成'
);
assert.equal(
  exchangeRecordsDefinition.normalizeRecord.call(exchangeRecordsPage, { status: 'COMPLETED' }).status,
  'completed',
  '后端状态进入筛选前必须归一为小写'
);

console.log('时光币兑换：动态分类、数量兑换、优惠券固定一张测试通过');
