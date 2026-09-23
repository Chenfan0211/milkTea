const config = require('../config');
const { request, unwrap } = require('./request');

/**
 * 小程序端登录态管理。
 *
 * 流程：
 *   wx.login() -> code -> POST /api/v1/app/auth/wx-login -> token
 *   token 存 Storage，后续请求由 utils/request.js 自动带上 Authorization。
 *
 * 说明：后端为「真模式」，必须配置 WX_APP_SECRET 才能登录成功；
 *       本地开发无合法域名时无法真机调试，属预期行为。
 */

const TOKEN_KEY = 'milkTea:auth:token';
const USER_KEY = 'milkTea:auth:user';
// 用户信息缓存：TTL 内直接读缓存，避免每次请求数据库
const USER_CACHE_KEY = 'milkTea:auth:user-cache';
const USER_CACHE_TTL = 5 * 60 * 1000; // 5 分钟

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
    // 忽略存储失败
  }
}

function removeStorage(key) {
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) wx.removeStorageSync(key);
  } catch (error) {
    // 忽略存储失败
  }
}

function getToken() {
  const token = readStorage(TOKEN_KEY);
  return typeof token === 'string' ? token : '';
}

function getCachedUser() {
  return readStorage(USER_KEY) || null;
}

function saveSession(payload) {
  if (payload && payload.token) writeStorage(TOKEN_KEY, payload.token);
  if (payload) writeStorage(USER_KEY, payload);
  return payload;
}

function clearSession() {
  removeStorage(TOKEN_KEY);
  removeStorage(USER_KEY);
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) {
      wx.removeStorageSync('milkTea:wx:session');
    }
  } catch (error) {
    // 忽略
  }
}

function isLoggedIn() {
  return Boolean(getToken());
}

/** wx.login 封装为 Promise */
function wxLogin() {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || !wx.login) {
      reject(new Error('wx.login 不可用'));
      return;
    }
    wx.login({
      success(res) {
        if (res && res.code) resolve(res.code);
        else reject(new Error('获取登录凭证失败'));
      },
      fail(err) {
        reject(err);
      }
    });
  });
}

/** 用 code 换取后端 token */
function loginWithCode(code) {
  return request({
    url: '/api/v1/app/auth/wx-login',
    method: 'POST',
    data: { code },
    skipAuth: true,
    mock() {
      // 仅用于本地无后端时的占位；真实登录必须走后端
      const fake = { code: 0, data: { token: '', userId: 0, openId: '', newUser: false }, message: 'ok' };
      return fake;
    }
  }).then(res => saveSession(unwrap(res)));
}

/** 静默登录：已登录则直接返回缓存，否则走 wx.login */
function ensureLogin() {
  const cached = getCachedUser();
  if (cached && cached.token) {
    return Promise.resolve(cached);
  }
  return wxLogin()
    .then(code => loginWithCode(code))
    .catch(error => {
      clearSession();
      throw error;
    });
}

/** 读取用户信息缓存（含时间戳），未过期返回缓存，避免重复请求数据库 */
function readUserCache() {
  try {
    if (typeof wx === 'undefined' || !wx.getStorageSync) return null;
    const raw = wx.getStorageSync(USER_CACHE_KEY);
    if (!raw || typeof raw !== 'object') return null;
    const { data, ts } = raw;
    if (!data || Date.now() - Number(ts || 0) > USER_CACHE_TTL) return null;
    return data;
  } catch (error) {
    return null;
  }
}

function writeUserCache(user) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync && user) {
      wx.setStorageSync(USER_CACHE_KEY, { data: user, ts: Date.now() });
    }
  } catch (error) {
    // 忽略
  }
}

/** 获取当前登录用户资料（优先缓存，5 分钟内不重复请求数据库） */
function fetchMe(force) {
  if (!force) {
    const cached = readUserCache();
    if (cached) return Promise.resolve(cached);
  }
  return request({ url: '/api/v1/app/auth/me', method: 'GET' }).then(res => {
    const user = unwrap(res);
    writeStorage(USER_KEY, Object.assign({}, getCachedUser() || {}, user));
    writeUserCache(Object.assign({}, getCachedUser() || {}, user));
    return user;
  });
}

/** 上报定位（需登录） */
function reportLocation(location) {
  if (!isLoggedIn()) return Promise.resolve(null);
  return request({
    url: '/api/v1/app/auth/location',
    method: 'POST',
    data: {
      latitude: location && location.latitude,
      longitude: location && location.longitude,
      address: location && location.address
    }
  }).catch(() => null);
}

/** 绑定手机号（解密 encryptedData） */
function bindPhone(encryptedData, iv) {
  return request({
    url: '/api/v1/app/auth/phone',
    method: 'POST',
    data: { encryptedData, iv }
  }).then(res => unwrap(res));
}

/** 更新头像昵称（解密 encryptedData） */
function bindProfile(encryptedData, iv) {
  return request({
    url: '/api/v1/app/auth/profile',
    method: 'POST',
    data: { encryptedData, iv }
  }).then(res => unwrap(res));
}

module.exports = {
  TOKEN_KEY,
  USER_KEY,
  getToken,
  getCachedUser,
  saveSession,
  clearSession,
  isLoggedIn,
  wxLogin,
  loginWithCode,
  ensureLogin,
  fetchMe,
  reportLocation,
  bindPhone,
  bindProfile
};
