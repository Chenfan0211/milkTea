const store = require('./store');
const api = require('./api');

/**
 * 设备定位能力（真实经纬度）。
 *
 * 设计要点：
 * - **授权先行**：先 wx.getSetting 查 scope.userLocation，未决定则 wx.authorize，
 *   已拒绝则引导 wx.openSetting（微信规定：拒绝后只能由用户主动去设置页开启，
 *   且 openSetting 必须由用户点击触发，不能程序化调起）。
 * - **失败必有降级**：任何一步失败都回落到城市中心坐标（store.resolveLocationContext），
 *   保证「就近排序」永远有可用原点，不会因为定位失败导致门店列表空白。
 * - **逆地理可选**：拿到经纬度后尝试服务端代理逆解析出城市/地址；失败不影响主流程
 *   （经纬度才是排序的关键，地址只是展示）。
 * - 隐私合规：定位属于用户主动行为，本模块只做「申请/引导/降级」，不静默采集。
 */

const LOCATION_SCOPE = 'scope.userLocation';
// 定位超时：超过即降级，避免用户卡在 loading
const LOCATION_TIMEOUT = 5000;
// 坐标缓存有效期：10 分钟内直接用缓存，避免频繁定位（耗电且触发频控）
const CACHE_TTL = 10 * 60 * 1000;

const LOCATION_SOURCE = {
  DEVICE: 'device',
  CACHE: 'cache',
  CITY_CENTER: 'city'
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
    // 存储不可用不影响定位结果
  }
}

/** 读取缓存的设备坐标（未过期才返回）。 */
function readCachedCoordinate(now = Date.now()) {
  const cached = readStorage(store.STORAGE_KEYS.LOCATION);
  if (!cached || typeof cached !== 'object') return null;
  if (cached.source !== LOCATION_SOURCE.DEVICE) return null;
  const ts = Number(cached.locatedAt) || 0;
  if (!ts || now - ts > CACHE_TTL) return null;
  if (!Number.isFinite(cached.latitude) || !Number.isFinite(cached.longitude)) return null;
  return cached;
}

/** 当前是否已经拿到过设备坐标（供页面显示「已定位」态）。 */
function hasDeviceLocation(now = Date.now()) {
  return Boolean(readCachedCoordinate(now));
}

/** wx.getSetting 封装。 */
function getSetting() {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || !wx.getSetting) {
      reject(new Error('wx.getSetting 不可用'));
      return;
    }
    wx.getSetting({
      success: resolve,
      fail: reject
    });
  });
}

/** wx.authorize 封装。 */
function authorize(scope) {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || !wx.authorize) {
      reject(new Error('wx.authorize 不可用'));
      return;
    }
    wx.authorize({
      scope,
      success: resolve,
      fail: reject
    });
  });
}

/** wx.getLocation 封装（带超时兜底）。 */
function getLocation(options = {}) {
  const timeout = Number(options.timeout) > 0 ? Number(options.timeout) : LOCATION_TIMEOUT;
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || !wx.getLocation) {
      reject(new Error('wx.getLocation 不可用'));
      return;
    }
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error('定位超时'));
    }, timeout);
    wx.getLocation({
      type: 'gcj02',
      isHighAccuracy: false,
      success(result) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(result);
      },
      fail(error) {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        reject(error);
      }
    });
  });
}

/**
 * 确保已获得 scope.userLocation 授权。
 * @returns {Promise<{granted: boolean, denied: boolean}>}
 *   granted=true 已授权；denied=true 用户曾拒绝（需引导去设置页开启）。
 */
function ensureScope() {
  return getSetting()
    .then(setting => {
      const authSetting = (setting && setting.authSetting) || {};
      const current = authSetting[LOCATION_SCOPE];
      // 已授权
      if (current === true) return { granted: true, denied: false };
      // 曾拒绝：微信不再允许弹系统授权框，只能引导到设置页
      if (current === false) return { granted: false, denied: true };
      // 未决定：发起授权
      return authorize(LOCATION_SCOPE)
        .then(() => ({ granted: true, denied: false }))
        .catch(() => ({ granted: false, denied: true }));
    })
    .catch(() => ({ granted: false, denied: true }));
}

/** 引导用户去设置页开启定位权限（openSetting 必须由用户点击触发）。 */
function openLocationSetting() {
  return new Promise(resolve => {
    if (typeof wx === 'undefined' || !wx.openSetting) {
      resolve(false);
      return;
    }
    wx.openSetting({
      success(res) {
        resolve(Boolean(res && res.authSetting && res.authSetting[LOCATION_SCOPE]));
      },
      fail() {
        resolve(false);
      }
    });
  });
}

/**
 * 取当前城市中心坐标作为降级原点。
 * 复用 store 模块的默认城市逻辑，保证与门店列表口径一致。
 */
function resolveFallbackLocation() {
  const context = store.resolveLocationContext();
  if (context && Number.isFinite(context.latitude) && Number.isFinite(context.longitude)) {
    return {
      latitude: context.latitude,
      longitude: context.longitude,
      cityCode: context.cityCode || '',
      cityName: context.cityName || '',
      address: context.address || '',
      source: LOCATION_SOURCE.CITY_CENTER
    };
  }
  return {
    latitude: 0,
    longitude: 0,
    cityCode: '',
    cityName: '',
    address: '',
    source: LOCATION_SOURCE.CITY_CENTER
  };
}

/**
 * 逆地理：经纬度 -> 城市/地址。
 * 服务端未配置腾讯密钥时返回空结果，此处静默回落，不影响定位主流程。
 */
function reverseGeocode(latitude, longitude) {
  return api
    .fetchReverseGeocode(latitude, longitude)
    .then(info => {
      if (!info || typeof info !== 'object') return null;
      return {
        address: info.address || info.formattedAddress || '',
        city: info.city || info.cityName || ''
      };
    })
    .catch(() => null);
}

/** 用经纬度匹配城市列表中最接近的城市（逆地理失败时的兜底）。 */
function matchNearestCity(latitude, longitude) {
  const cities = store.getCityList();
  if (!cities.length) return null;
  let best = null;
  let bestDistance = Infinity;
  cities.forEach(city => {
    if (!Number.isFinite(city.latitude) || !Number.isFinite(city.longitude)) return;
    const distance = store.calculateDistanceKm(
      { latitude, longitude },
      { latitude: city.latitude, longitude: city.longitude }
    );
    if (distance < bestDistance) {
      bestDistance = distance;
      best = city;
    }
  });
  return best;
}

/**
 * 获取设备定位（带缓存与全链路降级）。
 *
 * @param {Object} [options]
 *   - force: 忽略缓存强制重新定位
 *   - openSettingOnDenied: 被拒绝时是否弹窗引导去设置页（默认 true）
 * @returns {Promise<{latitude, longitude, cityCode, cityName, address, source, granted}>}
 *   永不 reject：失败时 source = 'city'，返回城市中心坐标。
 */
function locate(options = {}) {
  const opts = options || {};
  const openSettingOnDenied = opts.openSettingOnDenied !== false;

  if (!opts.force) {
    const cached = readCachedCoordinate();
    if (cached) {
      return Promise.resolve(
        Object.assign({}, cached, { source: LOCATION_SOURCE.CACHE, granted: true })
      );
    }
  }

  return ensureScope()
    .then(scope => {
      if (!scope.granted) {
        if (scope.denied && openSettingOnDenied) {
          return new Promise(resolve => {
            wx.showModal({
              title: '需要定位权限',
              content: '开启定位后可按距离为你排序附近门店，是否前往设置？',
              confirmText: '去设置',
              cancelText: '暂不开启',
              success(res) {
                if (!res || !res.confirm) {
                  resolve(null);
                  return;
                }
                openLocationSetting().then(resolve);
              },
              fail() {
                resolve(null);
              }
            });
          }).then(opened => {
            if (!opened) return null;
            // 用户在设置页开启后，再取一次坐标
            return getLocation({ timeout: opts.timeout }).catch(() => null);
          });
        }
        return null;
      }
      return getLocation({ timeout: opts.timeout }).catch(() => null);
    })
    .then(coords => {
      if (!coords || !Number.isFinite(coords.latitude) || !Number.isFinite(coords.longitude)) {
        // 定位失败 / 用户拒绝：降级到城市中心
        return Object.assign(resolveFallbackLocation(), { granted: false });
      }
      const latitude = coords.latitude;
      const longitude = coords.longitude;
      const fallbackCity = matchNearestCity(latitude, longitude);
      return reverseGeocode(latitude, longitude).then(info => {
        const cityName = (info && info.city) || (fallbackCity && fallbackCity.name) || '';
        const city = cityName ? store.getCityList().find(item => item.name === cityName) : null;
        const resolvedCity = city || fallbackCity;
        const context = {
          latitude,
          longitude,
          cityCode: resolvedCity ? resolvedCity.code : '',
          cityName,
          address: (info && info.address) || cityName,
          source: LOCATION_SOURCE.DEVICE,
          granted: true,
          locatedAt: Date.now()
        };
        saveDeviceLocation(context);
        return context;
      });
    });
}

/**
 * 保存设备定位结果。
 *
 * 说明：同时写入 store 的 LOCATION 缓存（供 resolveStoreCatalog 排序复用）
 * 并切换当前城市，保证「定位 -> 门店列表 -> 就近排序」口径一致。
 */
function saveDeviceLocation(context) {
  if (!context || !Number.isFinite(context.latitude) || !Number.isFinite(context.longitude)) {
    return context;
  }
  const payload = Object.assign({}, context, { locatedAt: context.locatedAt || Date.now() });
  store.saveLocationContext(payload);
  if (payload.cityCode) {
    store.selectCity(payload.cityCode);
  }
  return payload;
}

/** 清除设备定位缓存（用于「重新定位」强制刷新或隐私清理）。 */
function clearDeviceLocation() {
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) {
      wx.removeStorageSync(store.STORAGE_KEYS.LOCATION);
    }
  } catch (error) {
    // 忽略
  }
}

module.exports = {
  LOCATION_SCOPE,
  LOCATION_TIMEOUT,
  CACHE_TTL,
  LOCATION_SOURCE,
  hasDeviceLocation,
  readCachedCoordinate,
  ensureScope,
  openLocationSetting,
  getLocation,
  reverseGeocode,
  matchNearestCity,
  resolveFallbackLocation,
  locate,
  saveDeviceLocation,
  clearDeviceLocation
};