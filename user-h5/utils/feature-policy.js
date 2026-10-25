const buildPolicy = require('../config/build-policy');

const REPLACEMENT_TABS = {
  memberZone: {
    pagePath: '/pages/member-rights/member-rights',
    text: '会员专区',
    icon: '/assets/icons/lucide/member.svg',
    activeIcon: '/assets/icons/lucide/member-active.svg',
    feature: 'memberZone'
  }
};

const TONGUE_ROUTE_PREFIXES = ['/pages/tongue/', '/packageHealth/'];
let runtimePolicy = null;

function normalizePath(url) {
  const withoutQuery = String(url || '').split('?')[0];
  if (!withoutQuery) return '';
  return withoutQuery.charAt(0) === '/' ? withoutQuery : '/' + withoutQuery;
}

function normalizeRemotePolicy(policy) {
  const source = policy && typeof policy === 'object' ? policy : {};
  const sourceFeatures = source.features && typeof source.features === 'object' ? source.features : {};
  const features = {};

  Object.keys(sourceFeatures).forEach(name => {
    const raw = sourceFeatures[name] && typeof sourceFeatures[name] === 'object' ? sourceFeatures[name] : {};
    if (name !== 'tongueHealth') {
      features[name] = Object.assign({}, raw);
      return;
    }

    const seen = new Set();
    const hiddenVersions = (Array.isArray(raw.hiddenVersions) ? raw.hiddenVersions : [])
      .map(value => String(value || '').trim())
      .filter(value => {
        if (!value || seen.has(value)) return false;
        seen.add(value);
        return true;
      });
    const replacement = raw.replacement || 'memberZone';
    if (!REPLACEMENT_TABS[replacement]) {
      throw new Error(`Unsupported tab replacement: ${replacement}`);
    }
    features[name] = { hiddenVersions, replacement };
  });

  return {
    schemaVersion: Number(source.schemaVersion) || 1,
    features
  };
}

function getHiddenVersions(policy, featureName) {
  const normalized = normalizeRemotePolicy(policy);
  const feature = normalized.features[featureName];
  return feature && Array.isArray(feature.hiddenVersions) ? feature.hiddenVersions.slice() : [];
}

function isVersionHidden(policy, version) {
  const target = String(version || '').trim();
  if (!target) return false;
  return getHiddenVersions(policy, 'tongueHealth').includes(target);
}

function resolveFeatureVisibility(basePolicy, remotePolicyValue, version) {
  const base = basePolicy && typeof basePolicy === 'object' ? basePolicy : buildPolicy;
  const buildVersion = version || base.version;
  const remote = normalizeRemotePolicy(remotePolicyValue);
  const hiddenByBuild = base.features && base.features.tongueHealth === false;
  const hiddenByRemote = isVersionHidden(remote, buildVersion);
  return {
    tongueHealth: !hiddenByBuild && !hiddenByRemote,
    memberZone: Boolean(base.features && base.features.memberZone)
  };
}

function selectTabBarItems(basePolicy, visibility) {
  const base = basePolicy && typeof basePolicy === 'object' ? basePolicy : buildPolicy;
  const tabs = Array.isArray(base.tabBar) ? base.tabBar : [];
  return tabs.filter(item => !item.feature || visibility[item.feature] !== false);
}

function getVisibility() {
  return resolveFeatureVisibility(buildPolicy, runtimePolicy, buildPolicy.version);
}

function getTabBarItems() {
  return selectTabBarItems(buildPolicy, getVisibility()).map(item => Object.assign({}, item));
}

function getTabBarPages() {
  return getTabBarItems().map(item => item.pagePath);
}

function getThirdTabPath() {
  const items = getTabBarItems();
  const third = items[2] || items[items.length - 1];
  return third ? third.pagePath : '/pages/home/home';
}

function getTabBarSelectedIndex(url) {
  const target = normalizePath(url);
  return getTabBarPages().indexOf(target);
}

function shouldHideRoute(url, visibility) {
  const effective = visibility || getVisibility();
  if (effective.tongueHealth) return false;
  const target = normalizePath(url);
  return TONGUE_ROUTE_PREFIXES.some(prefix => target.indexOf(prefix) === 0);
}

function redirectIfHidden(url, fallback) {
  if (!shouldHideRoute(url)) return false;
  const visibility = getVisibility();
  const target = fallback || (visibility.memberZone ? getThirdTabPath() : '/pages/home/home');
  if (typeof wx === 'undefined') return true;
  if (getTabBarPages().indexOf(normalizePath(target)) !== -1 && wx.switchTab) {
    wx.switchTab({ url: normalizePath(target) });
  } else if (wx.reLaunch) {
    wx.reLaunch({ url: target });
  }
  return true;
}

function isMemberZoneTab() {
  const visibility = getVisibility();
  return visibility.memberZone && !visibility.tongueHealth;
}

function setRuntimePolicy(policy) {
  runtimePolicy = policy ? normalizeRemotePolicy(policy) : null;
  return runtimePolicy;
}

function refreshRemote(fetchConfig) {
  if (typeof fetchConfig !== 'function') return Promise.resolve(false);
  return Promise.resolve(fetchConfig('mini_release_policy'))
    .then(policy => {
      setRuntimePolicy(policy);
      return true;
    })
    .catch(() => false);
}

function resetRuntimePolicy() {
  runtimePolicy = null;
}

module.exports = {
  buildPolicy,
  getHiddenVersions,
  getTabBarItems,
  getTabBarPages,
  getTabBarSelectedIndex,
  getThirdTabPath,
  getVisibility,
  isMemberZoneTab,
  isVersionHidden,
  normalizePath,
  normalizeRemotePolicy,
  redirectIfHidden,
  refreshRemote,
  resetRuntimePolicy,
  resolveFeatureVisibility,
  selectTabBarItems,
  setRuntimePolicy,
  shouldHideRoute
};
