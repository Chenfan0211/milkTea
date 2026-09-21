import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = new Map();
globalThis.wx = {
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  },
  removeStorageSync(key) {
    storage.delete(key);
  }
};

const { cities, stores } = require(path.join(root, 'data/mock.js'));
const {
  DEFAULT_CITY_CODE,
  STORE_SELECTION_TTL,
  calculateDistanceKm,
  sortStoresByDistance,
  findNearestStore,
  handleAppHide,
  resolveLocationContext,
  resolveStorePreference,
  resolveStoreCatalog,
  getQueueStatus,
  getFavoriteStoreIds,
  isFavoriteStore,
  toggleFavoriteStore,
  selectCity,
  selectStore,
  useDeviceLocation
} = require(path.join(root, 'utils/store.js'));

assert.equal(cities.length, 3, '必须提供 3 个可服务城市');
assert.equal(stores.length, 9, '必须提供 3 城 9 店 Mock 数据');
assert.ok(
  stores.every(store => store.cityCode && Number.isFinite(store.latitude) && Number.isFinite(store.longitude)),
  '每家门店必须有城市和经纬度'
);
assert.ok(
  stores.every(
    store => Number.isInteger(store.queueCount) && !Object.prototype.hasOwnProperty.call(store, 'queueText')
  ),
  '每家门店必须有 queueCount 且不再保存 queueText'
);
assert.ok(
  stores.every(store => store.promotion === '新中式养生茶系列上新'),
  '所有门店必须使用统一的新中式养生茶促销文案'
);
assert.ok(
  stores.every(store => !Object.prototype.hasOwnProperty.call(store, 'decorImage')),
  '门店数据不得保留 decorImage'
);

const distance = calculateDistanceKm(
  { latitude: 28.2282, longitude: 112.9388 },
  { latitude: 28.2282, longitude: 112.9488 }
);
assert.ok(distance > 0.9 && distance < 1.1, '距离计算必须按公里返回近似直线距离');

const changshaStores = stores.filter(store => store.cityCode === DEFAULT_CITY_CODE);
const sortedStores = sortStoresByDistance(changshaStores, { latitude: 28.2282, longitude: 112.9388 });
assert.ok(sortedStores[0].distanceKm <= sortedStores[1].distanceKm, '门店必须按距离从近到远排序');
assert.equal(
  findNearestStore(DEFAULT_CITY_CODE, { latitude: 28.2282, longitude: 112.9388 }).id,
  sortedStores[0].id,
  '最近门店必须与排序结果一致'
);
assert.match(sortedStores[0].distanceText, /^距您\d+\.\dkm$/, '菜单门店距离必须保留一位小数');
assert.match(sortedStores[0].distanceLabel, /^直线\d+\.\d{2}km$/, '门店列表距离必须保留两位小数');

storage.clear();
const defaultLocation = resolveLocationContext();
assert.equal(defaultLocation.cityCode, DEFAULT_CITY_CODE, '无缓存位置时必须默认长沙');
assert.equal(defaultLocation.source, 'default', '默认位置的来源必须标记为 default');

storage.clear();
const firstCatalog = resolveStoreCatalog(1000);
assert.equal(firstCatalog.city.code, DEFAULT_CITY_CODE, '首次进入必须使用长沙');
assert.equal(firstCatalog.currentStore, null, '首次进入不得自动选中门店');
assert.equal(resolveStorePreference().activeStoreId, null, '首次进入不得写入门店缓存');

selectCity('guangzhou', 2000);
const cityCatalog = resolveStoreCatalog(2000);
assert.equal(cityCatalog.city.code, 'guangzhou', '手动切城后必须使用新城市');
assert.equal(cityCatalog.currentStore, null, '手动切城后不得自动预选门店');

selectStore('store-004', 3000);
assert.equal(resolveStoreCatalog(3000).currentStore.id, 'store-004', '用户选店后必须保留该门店');

handleAppHide(4000);
assert.equal(
  resolveStorePreference(4000 + STORE_SELECTION_TTL).activeStoreId,
  'store-004',
  '退出时间正好 30 分钟时必须保留门店'
);

handleAppHide(10000);
const expired = resolveStorePreference(10000 + STORE_SELECTION_TTL + 1);
assert.equal(expired.activeStoreId, null, '退出超过 30 分钟必须清除门店');
assert.equal(
  resolveStoreCatalog(10000 + STORE_SELECTION_TTL + 1).currentStore,
  null,
  '退出超时后必须停留选店状态，不得自动恢复最近门店'
);

selectCity('shenzhen', 20000);
useDeviceLocation(21000);
assert.equal(resolveLocationContext().cityCode, DEFAULT_CITY_CODE, '重新定位必须回到模拟设备位置长沙');
assert.equal(resolveStoreCatalog(21000).city.code, DEFAULT_CITY_CODE, '重新定位必须同步重置当前城市');
assert.equal(resolveStoreCatalog(21000).currentStore, null, '重新定位后必须重新选择门店');
assert.equal(getQueueStatus(5).level, 'safe', '5 杯必须为绿色状态');
assert.equal(getQueueStatus(6).level, 'warning', '6 杯必须为黄色状态');
assert.equal(getQueueStatus(10).level, 'warning', '10 杯必须为黄色状态');
assert.equal(getQueueStatus(11).level, 'danger', '11 杯必须为红色状态');
assert.equal(getQueueStatus(3).text, '前方3杯制作中', '排队文案必须与参考图一致且数字两侧无空格');
assert.ok(getQueueStatus(3).icon.includes('queue-safe'), '排队状态必须输出 Lucide 图标');
assert.deepEqual(getFavoriteStoreIds(), [], '收藏列表初始必须为空');
const firstFavorite = toggleFavoriteStore('store-001');
assert.equal(firstFavorite.favorite, true, '首次点击必须收藏门店');
assert.equal(isFavoriteStore('store-001'), true, '收藏后必须能读取收藏状态');
toggleFavoriteStore('store-004');
assert.deepEqual(getFavoriteStoreIds().sort(), ['store-001', 'store-004'], '多个门店收藏必须独立保存');
const removedFavorite = toggleFavoriteStore('store-001');
assert.equal(removedFavorite.favorite, false, '重复点击必须取消收藏');
assert.deepEqual(getFavoriteStoreIds(), ['store-004'], '取消收藏不得影响其他门店');

console.log('门店定位、排序与 30 分钟会话测试通过');
