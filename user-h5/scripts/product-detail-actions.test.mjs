import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const calls = [];
const storage = {};
const app = { globalData: { selectedStoreId: null, pendingRecommendationAdd: null, pendingOrder: null } };

globalThis.getApp = () => app;
globalThis.wx = {
  showShareMenu() {},
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
  showLoading(options) {
    calls.push({ type: 'loading', ...(options || {}) });
  },
  hideLoading() {
    calls.push({ type: 'hideLoading' });
  },
  navigateTo(options) {
    calls.push({ type: 'navigateTo', ...(options || {}) });
  },
  navigateBack(options) {
    calls.push({ type: 'navigateBack', ...(options || {}) });
  }
};

const auth = require(path.join(root, 'utils/auth.js'));
const api = require(path.join(root, 'utils/api.js'));
const cartState = require(path.join(root, 'utils/recommendation-cart-state.js'));
auth.saveSession({ token: 'token-detail', userId: 11 });

const PRODUCT = {
  id: 'tea-1',
  name: '花果香金骏眉',
  price: 1290,
  originalPrice: 1500,
  image: '/assets/images/3x/tea-1.jpg',
  specGroups: [
    {
      id: 'temperature',
      label: '状态',
      options: [
        { id: 'hot', label: '热饮', priceDelta: 0 },
        { id: 'cold', label: '冷饮', priceDelta: 0 }
      ]
    },
    {
      id: 'topping',
      label: '加料（必选1份）',
      options: [
        { id: 'none', label: '不加马蹄粉圆', priceDelta: 0, selected: true },
        { id: 'horseshoe', label: '加马蹄粉圆', priceDelta: 200 }
      ]
    }
  ]
};
api.fetchProductDetail = () => Promise.resolve(PRODUCT);

let definition = null;
globalThis.Page = options => {
  definition = options;
};
require(path.join(root, 'pages/product-detail/product-detail.js'));

function createPage() {
  const page = Object.assign({}, definition);
  page.data = Object.assign({}, definition.data);
  page.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return page;
}
async function flush() {
  await new Promise(resolve => setTimeout(resolve, 0));
}

const reportId = 'report-a';
cartState.clear(reportId);
const detailPage = createPage();
definition.onLoad.call(detailPage, { productId: 'tea-1', reportId });
await flush();

assert.equal(detailPage.data.selection.quantity, 1, '详情页默认数量必须为 1');
assert.equal(detailPage.data.selection.specGroups[0].options[0].selected, true, '规格默认选择后端默认项或第一项');
assert.equal(detailPage.data.selection.memberPrice, 12.9, '详情页初始价格必须使用商品最终售价');
assert.equal(detailPage.data.selection.priceMode, 'sale', '详情页必须标记最终售价模式');
assert.equal(detailPage.data.cartItems, undefined, '详情页不得维护购物车副本');

definition.selectSpecOption.call(detailPage, {
  currentTarget: { dataset: { groupId: 'temperature', optionId: 'cold' } }
});
definition.selectSpecOption.call(detailPage, {
  currentTarget: { dataset: { groupId: 'topping', optionId: 'horseshoe' } }
});
assert.equal(detailPage.data.selection.memberPrice, 14.9, '选择加料后主卡和底栏共用价格必须实时更新');
assert.deepEqual(
  detailPage.data.selection.selectedOptions.map(option => option.id),
  ['cold', 'horseshoe']
);

calls.length = 0;
definition.handleAddCart.call(detailPage);
const storedAfterAdd = cartState.getItems(reportId);
assert.equal(storedAfterAdd.length, 1, '详情页加购必须写入当前报告的推荐购物车');
assert.deepEqual(storedAfterAdd[0].selectedOptionIds, ['cold', 'horseshoe']);
assert.equal(storedAfterAdd[0].quantity, 1);
assert.equal(storedAfterAdd[0].price, 14.9, '加购价格必须保留详情页最终售价');
assert.equal(storedAfterAdd[0].priceMode, 'sale', '加购必须保留最终售价模式');
assert.ok(
  calls.some(call => call.type === 'navigateBack' && call.delta === 1),
  '加购成功后必须返回报告页'
);
assert.equal(definition.handleCartChange, undefined, '详情页不得保留购物车弹层回调');

calls.length = 0;
definition.handleBuyNow.call(detailPage);
assert.equal(app.globalData.pendingRecommendationAdd.intent, 'buyNow');
assert.equal(app.globalData.pendingRecommendationAdd.reportId, reportId, '立即购买必须保留报告上下文');
assert.deepEqual(app.globalData.pendingRecommendationAdd.items[0].selectedOptionIds, ['cold', 'horseshoe']);
assert.equal(app.globalData.pendingRecommendationAdd.items[0].quantity, 1);
assert.equal(app.globalData.pendingRecommendationAdd.items[0].priceMode, 'sale', '立即购买必须保留最终售价模式');
assert.ok(
  calls.some(
    call => call.type === 'navigateTo' && call.url.includes('/pages/recommendation-stores/recommendation-stores')
  )
);

const missingPage = createPage();
definition.onLoad.call(missingPage, { productId: 'tea-1' });
await flush();
calls.length = 0;
definition.handleAddCart.call(missingPage);
assert.ok(
  calls.some(call => call.type === 'toast' && call.title.includes('请从体质报告进入后加购')),
  '无报告上下文必须提示'
);
assert.equal(
  calls.some(call => call.type === 'navigateBack'),
  false,
  '无报告上下文不得误返回'
);
assert.deepEqual(cartState.getItems(''), [], '无报告上下文不得写入推荐购物车');

console.log('product-detail-actions.test.mjs: passed');
