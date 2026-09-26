const config = require('../config');

const TOKEN_STORAGE_KEY = 'milkTea:auth:token';

// 避免循环依赖：延迟到调用时才 require auth
let authModule = null;
function getAuth() {
  if (!authModule) {
    try {
      authModule = require('./auth');
    } catch (error) {
      authModule = null;
    }
  }
  return authModule;
}

// 登录失效后自动静默重登，成功后返回新 token。
//
// 并发去重统一由 auth.ensureLogin() 承担（微信 code 一次性，
// 多处并发 wx.login 会互相作废 → 40029）。这里只做「拿到 token 与否」的收敛。
function silentRelogin() {
  const auth = getAuth();
  if (!auth) return Promise.resolve('');
  return auth
    .ensureLogin(true)
    .then(user => (user && user.token) || '')
    .catch(() => '');
}

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

/**
 * 登录态失效处理。
 *
 * 页面常并发发起多个请求，若各自处理会连弹多次弹窗 / 连跳多次授权页。
 * 这里做节流：同一时间窗内只处理一次；并复用 login-guard 的页面栈检查，
 * 已处于授权页时不重复跳转。
 */
let unauthorizedNotifiedAt = 0;
const UNAUTHORIZED_TOAST_INTERVAL = 3000;

/** 登录态失效：先确认再跳授权登录页（避免只弹 Toast、用户无法登录） */
function handleUnauthorized(res) {
  if (typeof wx === 'undefined') return;
  const now = Date.now();
  if (now - unauthorizedNotifiedAt < UNAUTHORIZED_TOAST_INTERVAL) return;
  unauthorizedNotifiedAt = now;

  const message = (res && res.message) || '登录已过期，请重新登录';
  wx.showModal({
    title: '需要登录',
    content: message + '，是否前往登录？',
    confirmText: '去登录',
    cancelText: '取消',
    success: ({ confirm }) => {
      if (!confirm) return;
      let loginGuard = null;
      try {
        loginGuard = require('./login-guard');
      } catch (error) {
        loginGuard = null;
      }
      if (loginGuard && typeof loginGuard.openLoginSheet === 'function') {
        loginGuard.openLoginSheet({ reason: '登录后可继续操作' });
      }
    }
  });
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
        const body = res && res.data;
        // 登录态失效：静默重登后自动重试一次（避免 token 过期导致整个页面失效）
        // 加密数据类请求的 session_key 与本次授权绑定，不能拿旧 encryptedData/iv
        // 在静默重登后原样重试；调用方可显式关闭自动重试，由页面提示用户重新授权。
        if (body && (body.code === 8888 || body.code === 9999) &&
            !options.skipAuth && options.retryAuth !== false) {
          silentRelogin().then(token => {
            if (!token) {
              // 重登失败：清理会话并明确提示，避免页面静默卡在 401
              handleUnauthorized(body);
              resolve(body);
              return;
            }
            // 带上新 token 重试一次
            const retryHeader = Object.assign({}, header, { Authorization: `Bearer ${token}` });
            wx.request({
              url: (config.BASE_URL || '') + options.url,
              method: options.method || 'GET',
              data: options.data || {},
              header: retryHeader,
              timeout: options.timeout || 8000,
              success(retryRes) {
                resolve(retryRes && retryRes.data);
              },
              fail() {
                handleUnauthorized(body);
                resolve(body);
              }
            });
          });
          return;
        }
        // 直接返回后端响应体 { code, data, message }，由 unwrap 统一解析。
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
