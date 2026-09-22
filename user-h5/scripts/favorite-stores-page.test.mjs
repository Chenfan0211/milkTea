import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = new Map();
let capturedPage = null;
let navigatedBack = false;
const app = {
  globalData: {
    selectedStoreId: null,
    selectedCityCode: 'changsha',
    selectedCityName: '长沙市',
    favoriteStoreSelected: false
  }
};

globalThis.wx = {
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  },
  removeStorageSync(key) {
    storage.delete(key);
  },
  showShareMenu() {},
  reLaunch() {},
  navigateBack() {
    navigatedBack = true;
  },
  showToast() {}
};
// 门店 / 城市改为接口获取：从 DB seed 解析后经 wx.request 返回
const { loadStores, readAppConfig } = await import('./lib/seed-data.mjs');
const seedStores = loadStores();
const seedCities = readAppConfig('app_cities') || [];
globalThis.wx.request = function request(options) {
  const url = String(options.url || '');
  let data = [];
  if (url.includes('/app/stores')) data = seedStores;
  else if (url.includes('/config/cities')) data = seedCities;
  setTimeout(() => options.success({ statusCode: 200, data: { code: 0, data, message: 'ok' } }), 0);
};

globalThis.getApp = () => app;
globalThis.getCurrentPages = () => [];
globalThis.Page = config => {
  capturedPage = config;
};

const { selectCity, toggleFavoriteStore, refreshStoreCatalogFromRemote, refreshCitiesFromRemote } = require(path.join(root, 'utils/store.js'));
await refreshCitiesFromRemote();
await refreshStoreCatalogFromRemote();
selectCity('changsha', 1000);
toggleFavoriteStore('store-001');
toggleFavoriteStore('store-004');

require(path.join(root, 'pages/favorite-stores/favorite-stores.js'));
const page = Object.assign({}, capturedPage);
page.data = JSON.parse(JSON.stringify(capturedPage.data));
page.setData = updates => Object.assign(page.data, updates);
page.route = 'pages/favorite-stores/favorite-stores';
page.onLoad();
page.onShow();
assert.equal(page.data.currentCityName, '长沙市', '收藏页必须显示当前城市');
assert.deepEqual(
  page.data.favoriteStores.map(store => store.id),
  ['store-001'],
  '收藏页只能展示当前城市收藏门店'
);

page.handleRemoveFavorite({ detail: { store: page.data.favoriteStores.find(store => store.id === 'store-001') } });
assert.equal(page.data.favoriteStores.length, 0, '取消最后一项收藏后必须显示空状态');
assert.ok(storage.get('milkTea:favorite-store-ids').includes('store-004'), '取消当前城市收藏不得删除其他城市收藏');

toggleFavoriteStore('store-001');
page.loadFavorites();
page.handleSelectFavoriteStore({ detail: { store: page.data.favoriteStores.find(store => store.id === 'store-001') } });
assert.equal(app.globalData.selectedStoreId, 'store-001', '选择收藏门店必须更新当前门店');
assert.equal(app.globalData.favoriteStoreSelected, true, '选择收藏门店必须标记返回来源');
assert.equal(navigatedBack, true, '选择收藏门店必须返回菜单页');

console.log('收藏门店页与当前城市过滤测试通过');
