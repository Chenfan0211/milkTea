const config = require('../config');

const TOKEN_STORAGE_KEY = 'milkTea:auth:token';

/** 读取登录 token（避免与 auth.js 循环依赖，这里直接读 Storage） */
function readToken() {
  try {
    if (typeof wx === 'undefined' || !wx.getStorageSync) return '';
    const token = wx.getStorageSync(TOKEN_STORAGE_KEY);
    return typeof token === 'string' ? token : '';
  } catch (error) {
    return '';
  }
}

/** 登录态失效：清理会话并提示 */
function handleUnauthorized(res) {
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) {
      wx.removeStorageSync(TOKEN_STORAGE_KEY);
      wx.removeStorageSync('milkTea:auth:user');
    }
  } catch (error) {
    // 忽略
  }
  const message = (res && res.message) || '登录已过期，请重新登录';
  if (typeof wx !== 'undefined' && wx.showToast) {
    wx.showToast({ title: message, icon: 'none' });
  }
}

/**
 * 统一请求封装。
 * - USE_MOCK 为 true 时走 mockFn（开发/演示阶段无后端）。
 * - USE_MOCK=false 时走 wx.request，并自动携带 Authorization。
 * - skipAuth=true 的接口（如登录）不附带 token。
 */
function request(options) {
  return new Promise((resolve, reject) => {
    if (config.USE_MOCK && typeof options.mock === 'function') {
      // 模拟网络延迟，便于观察加载态
      setTimeout(() => {
        try {
          resolve(options.mock());
        } catch (error) {
          reject(error);
        }
      }, 120);
      return;
    }
    if (typeof wx === 'undefined' || !wx.request) {
      reject(new Error('wx.request 不可用'));
      return;
    }

    const header = Object.assign({ 'content-type': 'application/json' }, options.header || {});
    if (!options.skipAuth) {
      const token = readToken();
      if (token) header.Authorization = `Bearer ${token}`;
    }

    wx.request({
      url: (config.BASE_URL || '') + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      header,
      timeout: options.timeout || 8000,
      success(res) {
        // 登录态失效：统一提示并清理
        const body = res && res.data;
        if (body && (body.code === 8888 || body.code === 9999)) {
          handleUnauthorized(body);
        }
        // 直接返回后端响应体 { code, data, message }，由 unwrap 统一解析。
        // 修复：原实现包了一层 { statusCode, data }，导致 unwrap 永远解析失败。
        resolve(body);
      },
      fail(err) {
        reject(err);
      }
    });
  });
}

/**
 * 统一解析后端返回结构 { code, data, message }。
 * code === 0 或 code === 200 视为成功。
 */
function unwrap(res) {
  if (res && (res.code === 0 || res.code === 200)) return res.data;
  const message = (res && res.message) || '网络请求失败';
  const error = new Error(message);
  error.res = res;
  throw error;
}

module.exports = { request, unwrap, readToken, handleUnauthorized };
