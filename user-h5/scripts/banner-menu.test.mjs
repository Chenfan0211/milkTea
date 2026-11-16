import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = new Map();
const toasts = [];
const app = { globalData: { orderMode: 'pickup', pendingBannerProductId: null } };
globalThis.getApp = () => app;
globalThis.wx = {
  getStorageSync: key => storage.get(key) || '',
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  showToast: value => toasts.push(value),
  switchTab() {},
  navigateTo() {},
  request() {}
};
let definition;
globalThis.Page = value => {
  definition = value;
};
const { setMenuCatalogForTest } = require(path.join(root, 'utils/product-listing.js'));
require(path.join(root, 'pages/menu/menu.js'));
const page = {
  ...definition,
  data: { ...definition.data, currentStore: { id: 'near' } },
  setData(value) {
    Object.assign(this.data, value);
  },
  getTabBar: () => ({ setData() {} })
};

app.globalData.pendingBannerProductId = 'tea-1';
setMenuCatalogForTest([{ groups: [{ categories: [{ products: [{ id: 'tea-1', name: '茶一', price: 1200 }] }] }] }]);
page.openPendingBannerProduct();
assert.equal(page.data.specVisible, true, '本店可售时打开商品详情规格层');
assert.equal(page.data.specProduct.id, 'tea-1');
assert.equal(app.globalData.pendingBannerProductId, null, '跳转意图只能消费一次');

page.setData({ specVisible: false });
app.globalData.pendingBannerProductId = 'tea-2';
page.openPendingBannerProduct();
assert.equal(page.data.specVisible, false, '下架商品停留列表');
assert.equal(toasts.at(-1).icon, 'none');

console.log('banner-menu.test.mjs: passed');
