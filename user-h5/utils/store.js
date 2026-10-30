const api = require('./api');

// 门店 / 城市内存镜像。
//
// 阶段 C 后数据来源为后端接口：
// - 页面在 onLoad/onShow 调用 refreshStoreCatalogFromRemote() / refreshCitiesFromRemote() 拉取；
// - 拉取前的同步函数（getStoreById 等）读内存镜像，未拉取到时返回空集合，
//   页面需要按「无数据」处理，不再回退本地假数据。
let storeCatalog = [];
let cityCatalog = [];

// 城市目录的在途请求句柄：与门店目录同理，避免冷启动时
// app.onLaunch 与点单页 onLoad 对同一份城市列表并发重复请求。请求结束即清空。
let cityCatalogInflight = null;

/** 用远端数据刷新城市列表（含坐标，用于就近排序）；并发调用共享同一次网络请求。 */
function refreshCitiesFromRemote() {
  if (cityCatalogInflight) return cityCatalogInflight;
  cityCatalogInflight = api
    .fetchCities()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        cityCatalog = list.map(item => ({
          id: item.id != null ? Number(item.id) : null,
          code: item.code,
          name: item.name,
          provinceName: item.provinceName || '',
          parentId: item.parentId != null ? Number(item.parentId) : null,
          latitude: Number(item.latitude) || 0,
          longitude: Number(item.longitude) || 0
        }));
      }
      return cityCatalog;
    })
    .catch(() => cityCatalog)
    .then(result => {
      cityCatalogInflight = null;
      return result;
    });
  return cityCatalogInflight;
}

// 门店目录的在途请求句柄。
//
// 为什么需要：冷启动时 app.onLaunch 与点单页 onShow 会几乎同时调用本函数，
// 若不去重就会对同一份门店列表发两次请求（截图里出现两条 stores、其中一条 canceled）。
// 这里让「请求飞行期间的并发调用共享同一个 Promise」，请求结束即清空，
// 因此不会引入任何 TTL / 缓存一致性风险（每次真正进入页面仍会重新拉取）。
let storeCatalogInflight = null;

/** 用远端数据刷新本地镜像（页面 onLoad 时调用）；并发调用共享同一次网络请求。 */
function refreshStoreCatalogFromRemote() {
  if (storeCatalogInflight) return storeCatalogInflight;
  storeCatalogInflight = api
    .fetchStores()
    .then(list => {
      // 空数组也要覆盖。停用店已从接口消失时，若这里因 length 为 0 而保留旧目录，
      // 当前店会继续留在内存里被点单和下单使用。
      if (Array.isArray(list)) {
        storeCatalog = list.map(normalizeRemoteStore);
      }
      return storeCatalog;
    })
    .catch(() => storeCatalog)
    .then(result => {
      // 无论成功失败都清空在途句柄：下一次调用会发起新的真实请求
      storeCatalogInflight = null;
      return result;
    });
  return storeCatalogInflight;
}

/** 把后端门店结构映射为小程序页面使用的结构 */
function normalizeRemoteStore(item) {
  const cityCode = item.cityCode || item.city || '';
  return Object.assign({}, item, {
    // 前端内部标识沿用业务 code（收藏、路由、选店记忆都已依赖它）；
    // subjectId 单独保留后端数字主键，下单时 storeSubjectId 必须用它，
    // 否则后端 Long 反序列化失败会返回 500「服务器内部错误」。
    id: item.code || String(item.id),
    subjectId: item.id != null ? Number(item.id) : null,
    cityCode,
    businessHours: item.businessHours || '10:00-22:00',
    modes: Array.isArray(item.modes) && item.modes.length ? item.modes : ['pickup', 'dinein'],
    promotion: item.promotion || '',
    queueCount: Number(item.queueCount) || 0,
    latitude: Number(item.latitude) || 0,
    longitude: Number(item.longitude) || 0
  });
}

const DEFAULT_CITY_CODE = '4301';
const STORE_SELECTION_TTL = 30 * 60 * 1000;
const STORAGE_KEYS = {
  LOCATION: 'milkTea:location-context',
  STORE_PREFERENCE: 'milkTea:store-preference',
  LAST_HIDDEN_AT: 'milkTea:last-hidden-at',
  FAVORITE_STORE_IDS: 'milkTea:favorite-store-ids'
};

function readStorage(key) {
  try {
    return typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(key) : '';
  } catch (error) {
    return '';
  }
}

function writeStorage(key, value) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(key, value);
  } catch (error) {
    // 存储不可用时保持内存结果可用
  }
}

function removeStorage(key) {
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) wx.removeStorageSync(key);
  } catch (error) {
    // 忽略
  }
}

function getDefaultCity() {
  return cityCatalog.find(city => city.code === DEFAULT_CITY_CODE) || cityCatalog[0] || null;
}

/** 直接注入门店目录（仅供测试使用）。 */
function setStoreCatalogForTest(list) {
  storeCatalog = (Array.isArray(list) ? list : []).map(normalizeRemoteStore);
  return storeCatalog;
}

/** 直接注入城市目录（仅供测试使用）。 */
function setCityCatalogForTest(list) {
  cityCatalog = Array.isArray(list) ? list : [];
  return cityCatalog;
}

function getCityList() {
  return cityCatalog.slice();
}

function getCityByCode(cityCode) {
  return cityCatalog.find(city => city.code === cityCode) || null;
}

function getStoreById(storeId) {
  return storeCatalog.find(store => store.id === storeId) || null;
}

function getStoresByCity(cityCode) {
  return storeCatalog.filter(store => store.cityCode === cityCode);
}

function calculateDistanceKm(origin, destination) {
  if (!origin || !destination) return 0;
  const earthRadiusKm = 6371;
  const toRadians = value => (value * Math.PI) / 180;
  const latitudeDelta = toRadians(destination.latitude - origin.latitude);
  const longitudeDelta = toRadians(destination.longitude - origin.longitude);
  const originLatitude = toRadians(origin.latitude);
  const destinationLatitude = toRadians(destination.latitude);
  const haversine =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(originLatitude) * Math.cos(destinationLatitude) * Math.sin(longitudeDelta / 2) ** 2;
  return 2 * earthRadiusKm * Math.asin(Math.sqrt(haversine));
}

function getQueueStatus(queueCount) {
  const count = Math.max(0, Number(queueCount) || 0);
  // 无排队订单时不展示「前方N杯制作中」
  if (count <= 0) {
    return { level: 'none', text: '', icon: '', className: '' };
  }
  if (count > 10) {
    return {
      level: 'danger',
      text: `前方${count}杯制作中`,
      icon: '/assets/icons/lucide/queue-danger.svg',
      className: 'queue-status--danger'
    };
  }
  if (count > 5) {
    return {
      level: 'warning',
      text: `前方${count}杯制作中`,
      icon: '/assets/icons/lucide/queue-warning.svg',
      className: 'queue-status--warning'
    };
  }
  return {
    level: 'safe',
    text: `前方${count}杯制作中`,
    icon: '/assets/icons/lucide/queue-safe.svg',
    className: 'queue-status--safe'
  };
}

/**
 * 门店卡片状态行文案。
 *
 * 为什么：参考图里门店卡在店名下方有一行状态文案（如「现在下单，立即制作」）。
 * 排队中优先展示排队进度（信息量更大），无排队时展示营业状态。
 *
 * @param {Object} store 门店
 * @param {{level:string,text:string}} queueStatus 队列状态
 */
function getStoreStatusText(store, queueStatus) {
  if (queueStatus.text) return queueStatus.text;
  if (store.businessStatus === 'closed') return '暂停营业，敬请期待';
  return '现在下单，立即制作';
}

function decorateStore(store, origin) {
  const distanceValue = calculateDistanceKm(origin, store);
  const distance = distanceValue.toFixed(2);
  const distanceText = distanceValue.toFixed(1);
  const queueStatus = getQueueStatus(store.queueCount);
  return Object.assign({}, store, {
    distanceValue,
    distanceKm: `${distance}km`,
    distanceLabel: `直线${distance}km`,
    distanceText: `距您${distanceText}km`,
    queueText: queueStatus.text,
    queueLevel: queueStatus.level,
    queueIcon: queueStatus.icon,
    queueClass: queueStatus.className,
    statusText: getStoreStatusText(store, queueStatus)
  });
}

/**
 * 用服务端返回的真实驾车距离装饰门店列表。
 *
 * 为什么不在前端算：直线距离（Haversine）与实际驾车距离差异较大，
 * 用户看到「直线 13.70km」会与实际导航里程不符。真实距离由服务端代理
 * 腾讯距离矩阵获取（密钥不出服务器）。
 *
 * 降级策略：传入的`distances`为空（未配置密钥 / 腾讯失败 / 网络异常）时，
 * 整体回落到 `decorateStore` 的直线估算，保证门店列表始终可展示。
 *
 * @param {Array} storeList 门店列表
 * @param {Object} origin 用户位置 { latitude, longitude }
 * @param {Array<{index:number, distanceKm:number, durationMinutes:number}>} distances 服务端返回
 */
function decorateStoresWithRealDistance(storeList, origin, distances) {
  if (!Array.isArray(distances) || !distances.length) {
    return (storeList || []).map(store => decorateStore(store, origin));
  }
  return (storeList || []).map((store, index) => {
    const decorated = decorateStore(store, origin);
    const entry = distances.find(item => Number(item.index) === index);
    if (!entry || !Number.isFinite(Number(entry.distanceKm))) {
      return decorated;
    }
    const distanceValue = Number(entry.distanceKm);
    const durationMinutes = Number(entry.durationMinutes) || 0;
    return Object.assign({}, decorated, {
      distanceValue,
      distanceKm: `${distanceValue.toFixed(2)}km`,
      distanceLabel: `驾车${distanceValue.toFixed(2)}km`,
      distanceText: `距您${distanceValue.toFixed(1)}km`,
      durationMinutes,
      durationLabel: durationMinutes > 0 ? `约${Math.round(durationMinutes)}分钟` : '',
      distanceSource: 'server'
    });
  });
}

function sortStoresByDistance(storeList, origin) {
  return storeList
    .map(store => decorateStore(store, origin))
    .sort((left, right) => left.distanceValue - right.distanceValue);
}

function findNearestStore(cityCode, origin) {
  const nearestStores = sortStoresByDistance(getStoresByCity(cityCode), origin);
  return nearestStores[0] || null;
}

function getFavoriteStoreIds() {
  const stored = readStorage(STORAGE_KEYS.FAVORITE_STORE_IDS);
  return Array.isArray(stored) ? stored.slice() : [];
}

function isFavoriteStore(storeId) {
  return getFavoriteStoreIds().indexOf(storeId) !== -1;
}

function toggleFavoriteStore(storeId) {
  const favoriteStoreIds = getFavoriteStoreIds();
  const index = favoriteStoreIds.indexOf(storeId);
  const favorite = index === -1;
  if (favorite) {
    favoriteStoreIds.push(storeId);
  } else {
    favoriteStoreIds.splice(index, 1);
  }
  writeStorage(STORAGE_KEYS.FAVORITE_STORE_IDS, favoriteStoreIds);
  return { storeId, favorite, favoriteStoreIds };
}

function resolveLocationContext() {
  const stored = readStorage(STORAGE_KEYS.LOCATION);
  const city = stored && getCityByCode(stored.cityCode);
  if (stored && city && Number.isFinite(stored.latitude) && Number.isFinite(stored.longitude)) {
    return Object.assign({}, stored, { cityName: city.name });
  }

  const defaultCity = getDefaultCity();
  if (!defaultCity) {
    return { cityCode: DEFAULT_CITY_CODE, cityName: '', latitude: 0, longitude: 0, address: '', source: 'default' };
  }
  return {
    cityCode: defaultCity.code,
    cityName: defaultCity.name,
    latitude: defaultCity.latitude,
    longitude: defaultCity.longitude,
    address: defaultCity.name,
    source: 'default'
  };
}

function saveLocationContext(context) {
  writeStorage(STORAGE_KEYS.LOCATION, context);
  return context;
}

function resolveStorePreference(now = Date.now()) {
  const stored = readStorage(STORAGE_KEYS.STORE_PREFERENCE);
  const defaultCity = getDefaultCity();
  let preference = {
    cityCode: defaultCity ? defaultCity.code : DEFAULT_CITY_CODE,
    activeStoreId: null,
    selectedAt: 0
  };

  if (stored && typeof stored === 'object') {
    const selectedCity = getCityByCode(stored.cityCode) || defaultCity;
    preference = {
      cityCode: selectedCity ? selectedCity.code : DEFAULT_CITY_CODE,
      activeStoreId: typeof stored.activeStoreId === 'string' ? stored.activeStoreId : null,
      selectedAt: Number.isFinite(stored.selectedAt) ? stored.selectedAt : 0
    };
  }

  const activeStore = preference.activeStoreId ? getStoreById(preference.activeStoreId) : null;
  // 不在当前营业目录里，或档案已是停用，都不能继续当作当前店。
  if (!activeStore || activeStore.cityCode !== preference.cityCode || activeStore.businessStatus !== 'open') {
    preference.activeStoreId = null;
  }

  const lastHiddenAt = Number(readStorage(STORAGE_KEYS.LAST_HIDDEN_AT));
  if (Number.isFinite(lastHiddenAt) && lastHiddenAt > 0) {
    if (preference.activeStoreId && now - lastHiddenAt > STORE_SELECTION_TTL) {
      preference.activeStoreId = null;
      preference.selectedAt = 0;
    }
    removeStorage(STORAGE_KEYS.LAST_HIDDEN_AT);
  }

  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference);
  return preference;
}

function handleAppHide(now = Date.now()) {
  writeStorage(STORAGE_KEYS.LAST_HIDDEN_AT, now);
}

function selectCity(cityCode, now = Date.now()) {
  const city = getCityByCode(cityCode);
  if (!city) return resolveStorePreference(now);
  const preference = resolveStorePreference(now);
  preference.cityCode = city.code;
  preference.activeStoreId = null;
  preference.selectedAt = now;
  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference);
  return preference;
}

function selectStore(storeId, now = Date.now()) {
  const store = getStoreById(storeId);
  if (!store) return resolveStorePreference(now);
  const preference = resolveStorePreference(now);
  preference.cityCode = store.cityCode;
  preference.activeStoreId = store.id;
  preference.selectedAt = now;
  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference);
  return preference;
}

function useDeviceLocation(now = Date.now()) {
  const location = resolveLocationContext();
  const city = getCityByCode(location.cityCode) || getDefaultCity();
  const preference = {
    cityCode: city ? city.code : DEFAULT_CITY_CODE,
    activeStoreId: null,
    selectedAt: now
  };
  writeStorage(STORAGE_KEYS.STORE_PREFERENCE, preference);
  return { location, city, preference };
}

function getSortOrigin(city, location) {
  if (location && city && location.cityCode === city.code) {
    return { latitude: location.latitude, longitude: location.longitude };
  }
  return city ? { latitude: city.latitude, longitude: city.longitude } : { latitude: 0, longitude: 0 };
}

function resolveStoreCatalog(now = Date.now()) {
  const preference = resolveStorePreference(now);
  // 城市数据来自接口，拉取失败时降级为安全空对象，避免下游访问 null 崩溃
  const city = getCityByCode(preference.cityCode) ||
    getDefaultCity() || {
      code: DEFAULT_CITY_CODE,
      name: '长沙市',
      initial: 'C',
      latitude: 0,
      longitude: 0
    };
  const location = resolveLocationContext();
  const origin = getSortOrigin(city, location);
  const cityStores = city ? sortStoresByDistance(getStoresByCity(city.code), origin) : [];
  const currentStore = preference.activeStoreId
    ? cityStores.find(store => store.id === preference.activeStoreId) || null
    : null;

  return {
    preference,
    location,
    city,
    origin,
    stores: cityStores,
    currentStore
  };
}

module.exports = {
  DEFAULT_CITY_CODE,
  setStoreCatalogForTest,
  setCityCatalogForTest,
  refreshStoreCatalogFromRemote,
  refreshCitiesFromRemote,
  normalizeRemoteStore,
  STORE_SELECTION_TTL,
  STORAGE_KEYS,
  calculateDistanceKm,
  decorateStore,
  decorateStoresWithRealDistance,
  findNearestStore,
  getCityByCode,
  getCityList,
  getStoreById,
  getStoresByCity,
  getFavoriteStoreIds,
  getQueueStatus,
  getStoreStatusText,
  handleAppHide,
  isFavoriteStore,
  resolveLocationContext,
  resolveStoreCatalog,
  resolveStorePreference,
  saveLocationContext,
  selectCity,
  selectStore,
  sortStoresByDistance,
  toggleFavoriteStore,
  useDeviceLocation
};
