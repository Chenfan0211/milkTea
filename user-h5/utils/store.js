const api = require('./api');

// 门店 / 城市内存镜像。
//
// 阶段 C 后数据来源为后端接口：
// - 页面在 onLoad/onShow 调用 refreshStoreCatalogFromRemote() / refreshCitiesFromRemote() 拉取；
// - 拉取前的同步函数（getStoreById 等）读内存镜像，未拉取到时返回空集合，
//   页面需要按「无数据」处理，不再回退本地假数据。
let storeCatalog = [];
let cityCatalog = [];

/** 用远端数据刷新城市列表（含坐标，用于就近排序） */
function refreshCitiesFromRemote() {
  return api
    .fetchCities()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        cityCatalog = list.map(item => ({
          code: item.code,
          name: item.name,
          initial: item.initial,
          latitude: Number(item.latitude) || 0,
          longitude: Number(item.longitude) || 0
        }));
      }
      return cityCatalog;
    })
    .catch(() => cityCatalog);
}

/** 用远端数据刷新本地镜像（页面 onLoad 时调用） */
function refreshStoreCatalogFromRemote() {
  return api
    .fetchStores()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        storeCatalog = list.map(normalizeRemoteStore);
      }
      return storeCatalog;
    })
    .catch(() => storeCatalog);
}

/** 把后端门店结构映射为小程序页面使用的结构 */
function normalizeRemoteStore(item) {
  const cityCode = item.city === '广州市' ? 'guangzhou' : item.city === '深圳市' ? 'shenzhen' : 'changsha';
  return Object.assign({}, item, {
    id: item.code || String(item.id),
    cityCode,
    businessHours: item.businessHours || '10:00-22:00',
    modes: Array.isArray(item.modes) && item.modes.length ? item.modes : ['pickup', 'dinein'],
    promotion: item.promotion || '',
    queueCount: Number(item.queueCount) || 0,
    latitude: Number(item.latitude) || 0,
    longitude: Number(item.longitude) || 0
  });
}

const DEFAULT_CITY_CODE = 'changsha';
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
    queueClass: queueStatus.className
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
  if (!activeStore || activeStore.cityCode !== preference.cityCode) {
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
  const city = getCityByCode(preference.cityCode) || getDefaultCity() || {
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
  findNearestStore,
  getCityByCode,
  getCityList,
  getStoreById,
  getStoresByCity,
  getFavoriteStoreIds,
  getQueueStatus,
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
