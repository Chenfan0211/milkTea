const config = require('../config');
const { request, unwrap } = require('./request');
const { storeTypes: fallbackStoreTypes } = require('../data/mock');

const STORE_TYPE_CACHE_KEY = 'milkTea:store-types:cache';

/**
 * 拉取门店类型字典。
 * 优先读缓存（有效期内），否则请求接口；请求失败回退本地默认字典。
 * 返回 [{ id, code, name, sort, enabled }]。
 */
function fetchStoreTypesFromRemote() {
  return request({
    url: '/api/v1/app/store-types',
    method: 'GET',
    mock() {
      // 模拟后端响应：与后台 storeTypes 对齐，只返回启用项并按 sort 升序
      const items = fallbackStoreTypes
        .filter(item => item.enabled !== false)
        .map(item => ({ id: item.id, code: item.code, name: item.name, sort: item.sort, enabled: true }))
        .sort((a, b) => (a.sort || 0) - (b.sort || 0));
      return { code: 0, data: items, message: 'ok' };
    }
  });
}

function readCache() {
  try {
    if (typeof wx === 'undefined' || !wx.getStorageSync) return null;
    const cached = wx.getStorageSync(STORE_TYPE_CACHE_KEY);
    if (!cached || !Array.isArray(cached.data)) return null;
    if (Date.now() - cached.ts > config.STORE_TYPE_CACHE_TTL) return null;
    return cached.data;
  } catch (error) {
    return null;
  }
}

function writeCache(data) {
  try {
    if (typeof wx === 'undefined' || !wx.setStorageSync) return;
    wx.setStorageSync(STORE_TYPE_CACHE_KEY, { data, ts: Date.now() });
  } catch (error) {
    // 缓存失败不影响主流程
  }
}

function normalize(items) {
  return (Array.isArray(items) ? items : [])
    .filter(item => item && item.enabled !== false)
    .sort((a, b) => (a.sort || 0) - (b.sort || 0));
}

/**
 * 获取门店类型（异步）。
 * 返回 Promise<Array<{ id, code, name, sort, enabled }>>。
 */
function getStoreTypes() {
  const cached = readCache();
  if (cached && cached.length) return Promise.resolve(cached);

  return fetchStoreTypesFromRemote()
    .then(res => {
      const items = normalize(unwrap(res));
      if (items.length) writeCache(items);
      return items.length ? items : normalize(fallbackStoreTypes);
    })
    .catch(() => normalize(fallbackStoreTypes));
}

module.exports = { getStoreTypes };