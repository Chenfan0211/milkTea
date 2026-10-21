import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = {};
const calls = [];
const app = { globalData: { pendingRecommendationAdd: null } };

globalThis.getApp = () => app;
globalThis.wx = {
  getStorageSync: key => (storage[key] === undefined ? '' : storage[key]),
  setStorageSync: (key, value) => {
    storage[key] = value;
  },
  removeStorageSync: key => {
    delete storage[key];
  },
  showToast(options) {
    calls.push({ type: 'toast', ...(options || {}) });
  },
  navigateTo(options) {
    calls.push({ type: 'navigateTo', ...(options || {}) });
  },
  navigateBack() {},
  switchTab() {},
  showModal() {},
  showLoading() {},
  hideLoading() {},
  createInnerAudioContext() {
    return {};
  }
};

let definition = null;
globalThis.Page = options => {
  definition = options;
};
require(path.join(root, 'packageHealth/pages/report/report.js'));

function createPage(cartItems) {
  const page = Object.assign({}, definition);
  page.reportId = 'report-a';
  page.data = Object.assign({}, definition.data, { cartItems });
  page.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return page;
}

function item(id, selected) {
  return {
    id,
    productId: id,
    name: id,
    image: id + '.jpg',
    selectedOptionIds: ['small'],
    quantity: 1,
    priceMode: 'sale',
    selected
  };
}

function reset() {
  calls.length = 0;
  app.globalData.pendingRecommendationAdd = null;
}

// 1) 直接点底部 cart-bar：事件没有 items，必须回退到页面里的选中商品
reset();
const barPage = createPage([item('selected-tea', true), item('unselected-tea', false)]);
definition.handleCartCheckout.call(barPage, {});
assert.deepEqual(
  app.globalData.pendingRecommendationAdd.items.map(entry => entry.productId),
  ['selected-tea']
);
assert.equal(app.globalData.pendingRecommendationAdd.reportId, 'report-a');
assert.equal(app.globalData.pendingRecommendationAdd.intent, 'cartCheckout');
assert.equal(app.globalData.pendingRecommendationAdd.cartScope, 'recommendation');
assert.equal(app.globalData.pendingRecommendationAdd.items[0].priceMode, 'sale', '报告购物车结算必须保留最终售价模式');
assert.ok(
  calls.some(
    call => call.type === 'navigateTo' && call.url.includes('/pages/recommendation-stores/recommendation-stores')
  )
);
assert.equal(
  calls.some(call => call.type === 'toast' && call.title === '请选择商品'),
  false
);

// 2) 购物车弹层发起结算：事件 items 优先于页面缓存
reset();
const sheetPage = createPage([item('fallback-tea', true)]);
definition.handleCartCheckout.call(sheetPage, { detail: { items: [item('sheet-tea', true)] } });
assert.deepEqual(
  app.globalData.pendingRecommendationAdd.items.map(entry => entry.productId),
  ['sheet-tea']
);
assert.ok(
  calls.some(
    call => call.type === 'navigateTo' && call.url.includes('/pages/recommendation-stores/recommendation-stores')
  )
);

// 3) 页面没有选中商品且事件也没有明细：保持原提示且不跳转
reset();
const emptyPage = createPage([item('unselected-tea', false)]);
definition.handleCartCheckout.call(emptyPage, {});
assert.equal(app.globalData.pendingRecommendationAdd, null);
assert.ok(calls.some(call => call.type === 'toast' && call.title === '请选择商品'));
assert.equal(
  calls.some(call => call.type === 'navigateTo'),
  false
);

console.log('report-cart-checkout.test.mjs: passed');
