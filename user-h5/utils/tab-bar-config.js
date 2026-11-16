const buildPolicy = require('../config/build-policy');

const SCHEMA_VERSION = 2;
const DEFAULT_THIRD_TAB = 'memberZone';
const CACHE_PREFIX = 'milkTea:mini-tab-bar:v2';
const LOAD_TIMEOUT = 1500;
const THIRD_TAB_PATH = '/pages/feature-tab/feature-tab';
const VERSION_PATTERN = /^[A-Za-z0-9.]+$/;

const THIRD_TABS = {
  memberZone: {
    pagePath: THIRD_TAB_PATH,
    text: '会员专区',
    icon: '/assets/icons/lucide/member.svg',
    activeIcon: '/assets/icons/lucide/member-active.svg'
  },
  tongueHealth: {
    pagePath: THIRD_TAB_PATH,
    text: '一人一饮',
    icon: '/assets/icons/lucide/personalized-drink.svg',
    activeIcon: '/assets/icons/lucide/personalized-drink-active.svg'
  }
};

const FIXED_TABS = [
  {
    pagePath: '/pages/home/home',
    text: '首页',
    icon: '/assets/icons/lucide/home.svg',
    activeIcon: '/assets/icons/lucide/home-active.svg'
  },
  {
    pagePath: '/pages/menu/menu',
    text: '点单',
    icon: '/assets/icons/lucide/menu.svg',
    activeIcon: '/assets/icons/lucide/menu-active.svg'
  },
  null,
  {
    pagePath: '/pages/orders/orders',
    text: '订单',
    icon: '/assets/icons/lucide/orders.svg',
    activeIcon: '/assets/icons/lucide/orders-active.svg'
  },
  {
    pagePath: '/pages/profile/profile',
    text: '我的',
    icon: '/assets/icons/lucide/profile.svg',
    activeIcon: '/assets/icons/lucide/profile-active.svg'
  }
];

const TONGUE_ROUTE_PREFIXES = ['/pages/tongue/', '/packageHealth/'];
let currentConfig = defaultResolved('dev');
let loadSource = 'default';
let loadPromise = null;

function normalizeVersion(value) {
  return String(value || '').trim();
}

function assertThirdTab(value, field) {
  if (!Object.prototype.hasOwnProperty.call(THIRD_TABS, value)) {
    throw new Error(`mini_tab_bar.${field} 仅支持 memberZone 或 tongueHealth`);
  }
}

function normalize(value) {
  const source = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  const allowedKeys = new Set(['schemaVersion', 'defaultThirdTab', 'releases']);
  const unknownKeys = Object.keys(source).filter(key => !allowedKeys.has(key));
  if (unknownKeys.length) {
    throw new Error('mini_tab_bar 仅支持 schemaVersion、defaultThirdTab 和 releases');
  }
  if (Number(source.schemaVersion) !== SCHEMA_VERSION) {
    throw new Error(`mini_tab_bar.schemaVersion 必须为 ${SCHEMA_VERSION}`);
  }
  assertThirdTab(source.defaultThirdTab, 'defaultThirdTab');
  const rawReleases = source.releases;
  if (!rawReleases || typeof rawReleases !== 'object' || Array.isArray(rawReleases)) {
    throw new Error('mini_tab_bar.releases 必须是版本到第三项的映射对象');
  }
  const releases = {};
  Object.keys(rawReleases).forEach(version => {
    const normalizedVersion = normalizeVersion(version);
    if (!VERSION_PATTERN.test(normalizedVersion)) {
      throw new Error(`mini_tab_bar.releases 版本号仅允许字母、数字和小数点: ${version}`);
    }
    assertThirdTab(rawReleases[version], 'releases');
    releases[normalizedVersion] = rawReleases[version];
  });
  return {
    schemaVersion: SCHEMA_VERSION,
    defaultThirdTab: source.defaultThirdTab,
    releases
  };
}

function defaultPolicy() {
  return normalize({
    schemaVersion: SCHEMA_VERSION,
    defaultThirdTab: DEFAULT_THIRD_TAB,
    releases: {}
  });
}

function defaultResolved(appVersion) {
  return {
    schemaVersion: SCHEMA_VERSION,
    thirdTab: DEFAULT_THIRD_TAB,
    appVersion: normalizeVersion(appVersion) || 'dev'
  };
}

function resolveThirdTab(config, version) {
  const normalized = normalize(config);
  const appVersion = normalizeVersion(version);
  return Object.prototype.hasOwnProperty.call(normalized.releases, appVersion)
    ? normalized.releases[appVersion]
    : normalized.defaultThirdTab;
}

function readAccountVersion() {
  if (typeof wx === 'undefined' || typeof wx.getAccountInfoSync !== 'function') return '';
  try {
    const account = wx.getAccountInfoSync() || {};
    return normalizeVersion(account.miniProgram && account.miniProgram.version);
  } catch (error) {
    return '';
  }
}

function resolveAppVersion(explicitVersion) {
  return normalizeVersion(explicitVersion) || readAccountVersion() || normalizeVersion(buildPolicy.version) || 'dev';
}

function cacheKeyForVersion(version) {
  return `${CACHE_PREFIX}:${encodeURIComponent(resolveAppVersion(version))}`;
}

function normalizeResolved(value) {
  const source = value && typeof value === 'object' ? value : {};
  if (Number(source.schemaVersion) !== SCHEMA_VERSION) {
    throw new Error(`导航缓存 schemaVersion 必须为 ${SCHEMA_VERSION}`);
  }
  assertThirdTab(source.thirdTab, 'thirdTab');
  return {
    schemaVersion: SCHEMA_VERSION,
    thirdTab: source.thirdTab
  };
}

function readCache(cacheKey) {
  if (typeof wx === 'undefined' || typeof wx.getStorageSync !== 'function') return null;
  try {
    const raw = wx.getStorageSync(cacheKey);
    if (!raw) return null;
    return normalizeResolved(typeof raw === 'string' ? JSON.parse(raw) : raw);
  } catch (error) {
    return null;
  }
}

function writeCache(cacheKey, config) {
  if (typeof wx === 'undefined' || typeof wx.setStorageSync !== 'function') return;
  try {
    wx.setStorageSync(cacheKey, JSON.stringify(config));
  } catch (error) {
    // 本地缓存失败不阻断导航展示。
  }
}

function withTimeout(promise, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('导航配置请求超时')), timeoutMs);
    Promise.resolve(promise).then(
      value => {
        clearTimeout(timer);
        resolve(value);
      },
      error => {
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

function applyResolved(thirdTab, appVersion) {
  currentConfig = {
    schemaVersion: SCHEMA_VERSION,
    thirdTab,
    appVersion
  };
  return Object.assign({}, currentConfig);
}

function load(fetchConfig, options = {}) {
  if (loadPromise && !options.force) return loadPromise;
  const timeout = Number(options.timeout) > 0 ? Number(options.timeout) : LOAD_TIMEOUT;
  const appVersion = resolveAppVersion(options.version);
  const cacheKey = cacheKeyForVersion(appVersion);

  if (typeof fetchConfig !== 'function') {
    const cached = readCache(cacheKey);
    loadSource = cached ? 'cache' : 'default';
    loadPromise = Promise.resolve(applyResolved(cached ? cached.thirdTab : DEFAULT_THIRD_TAB, appVersion));
    return loadPromise;
  }

  loadPromise = withTimeout(fetchConfig('mini_tab_bar', { timeout }), timeout)
    .then(normalize)
    .then(config => {
      const thirdTab = resolveThirdTab(config, appVersion);
      const resolved = applyResolved(thirdTab, appVersion);
      loadSource = 'remote';
      writeCache(cacheKey, resolved);
      return resolved;
    })
    .catch(() => {
      const cached = readCache(cacheKey);
      loadSource = cached ? 'cache' : 'default';
      return applyResolved(cached ? cached.thirdTab : DEFAULT_THIRD_TAB, appVersion);
    });
  return loadPromise;
}

function ready(fetchConfig, options) {
  return load(fetchConfig, options);
}

function getCurrent() {
  return Object.assign({}, currentConfig);
}

function getTabBarItems() {
  const third = THIRD_TABS[currentConfig.thirdTab];
  return FIXED_TABS.map(item => (item ? Object.assign({}, item) : Object.assign({}, third)));
}

function getTabBarPages() {
  return getTabBarItems().map(item => item.pagePath);
}

function getThirdTabPath() {
  return THIRD_TAB_PATH;
}

function getTabBarSelectedIndex(url) {
  const withoutQuery = String(url || '').split('?')[0];
  const target = withoutQuery.charAt(0) === '/' ? withoutQuery : `/${withoutQuery}`;
  return getTabBarPages().indexOf(target);
}

function isMemberZoneTab() {
  return currentConfig.thirdTab === 'memberZone';
}

function normalizePath(url) {
  const withoutQuery = String(url || '').split('?')[0];
  return withoutQuery.charAt(0) === '/' ? withoutQuery : `/${withoutQuery}`;
}

function shouldHideRoute(url, config = currentConfig) {
  if (config.thirdTab !== 'memberZone') return false;
  const target = normalizePath(url);
  return TONGUE_ROUTE_PREFIXES.some(prefix => target.indexOf(prefix) === 0);
}

function redirectIfHidden(url, fallback) {
  if (!shouldHideRoute(url)) return false;
  const target = fallback || THIRD_TAB_PATH;
  if (typeof wx === 'undefined') return true;
  if (typeof wx.switchTab === 'function') {
    wx.switchTab({ url: target });
  } else if (typeof wx.reLaunch === 'function') {
    wx.reLaunch({ url: target });
  }
  return true;
}

function getLoadSource() {
  return loadSource;
}

function resetForTest() {
  currentConfig = defaultResolved('dev');
  loadSource = 'default';
  loadPromise = null;
}

module.exports = {
  CACHE_PREFIX,
  DEFAULT_THIRD_TAB,
  LOAD_TIMEOUT,
  SCHEMA_VERSION,
  THIRD_TAB_PATH,
  cacheKeyForVersion,
  getCurrent,
  getLoadSource,
  getTabBarItems,
  getTabBarPages,
  getTabBarSelectedIndex,
  getThirdTabPath,
  isMemberZoneTab,
  load,
  normalize,
  normalizePath,
  ready,
  redirectIfHidden,
  resetForTest,
  resolveAppVersion,
  resolveThirdTab,
  shouldHideRoute
};
