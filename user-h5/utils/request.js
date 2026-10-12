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

/**
 * 把后端返回的登录态错误码转成用户可读文案。
 * 区分「令牌无效」与「登录过期」，避免用户明明刚登录却看到笼统的「未登录」。
 */
function describeAuthError(res) {
  const code = res && res.code;
  if (code === 9999) return '登录已过期，请重新登录';
  if (code === 8888) return '登录状态已失效，请重新登录';
  return (res && res.message) || '登录状态已失效，请重新登录';
}

/**
 * 需要登录的请求返回 401 时的统一处理。区分两种情形：
 *
 *   1) 匿名用户（本地无 token，如冷启动未授权就浏览）：只是「该功能需要登录」，
 *      弹温和引导而非「登录已失效」，否则新用户一进来就被吓到，也不符合
 *      「先浏览后授权」的合规要求（历史问题：首页误请求 /me 触发此弹窗）。
 *   2) 已登录但 token 失效（静默重登也失败）：此时弹「登录已失效，是否重新登录」。
 *
 * 页面常并发发起多个请求，若各自处理会连弹多次，这里统一节流。
 */
function handleUnauthorized(res) {
  if (typeof wx === 'undefined') return;
  const now = Date.now();
  if (now - unauthorizedNotifiedAt < UNAUTHORIZED_TOAST_INTERVAL) return;
  unauthorizedNotifiedAt = now;

  const openGuard = () => {
    let loginGuard = null;
    try {
      loginGuard = require('./login-guard');
    } catch (error) {
      loginGuard = null;
    }
    return loginGuard;
  };

  // 匿名用户：温和引导（非「失效」语义）
  if (!readToken()) {
    wx.showModal({
      title: '登录后可查看',
      content: '该功能需要登录后使用，是否前往登录？',
      confirmText: '去登录',
      cancelText: '暂不',
      success: ({ confirm }) => {
        if (!confirm) return;
        const loginGuard = openGuard();
        if (loginGuard && typeof loginGuard.openLoginSheet === 'function') {
          loginGuard.openLoginSheet({ reason: '登录后可查看该功能' });
        }
      }
    });
    return;
  }

  // 已登录但 token 失效：提示重新登录
  const message = describeAuthError(res);
  wx.showModal({
    title: '登录已失效',
    content: message + '，是否重新登录？',
    confirmText: '重新登录',
    cancelText: '取消',
    success: ({ confirm }) => {
      if (!confirm) return;
      const loginGuard = openGuard();
      if (loginGuard && typeof loginGuard.openLoginSheet === 'function') {
        loginGuard.openLoginSheet({ reason: message });
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
        if (body && (body.code === 8888 || body.code === 9999) && !options.skipAuth && options.retryAuth !== false) {
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
