const config = require('../config');
const { request, unwrap } = require('./request');
const referrer = require('./referrer');

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
// 未注册用户的一次性注册凭证（后端按 openid 查无此人时下发）
const REGISTER_KEY = 'milkTea:auth:register';

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

/** 读取未注册用户的一次性注册上下文（{ registerToken, openId }） */
function getRegisterContext() {
  const ctx = readStorage(REGISTER_KEY);
  return ctx && typeof ctx === 'object' ? ctx : null;
}

function saveRegisterContext(ctx) {
  if (ctx && typeof ctx === 'object') writeStorage(REGISTER_KEY, ctx);
}

function clearRegisterContext() {
  removeStorage(REGISTER_KEY);
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
  }).then(res => {
    const data = unwrap(res);
    if (data && data.token) {
      // 已注册：写入登录态
      saveSession(data);
      clearRegisterContext();
    } else if (data && data.registerToken) {
      // 未注册：只保留一次性注册凭证，不建立登录态
      saveRegisterContext({ registerToken: data.registerToken, openId: data.openId || '' });
      clearSession();
    } else {
      clearSession();
      clearRegisterContext();
      throw new Error('登录服务响应异常，请稍后重试');
    }
    return data;
  });
}

/**
 * 静默登录：已登录则直接返回缓存，否则走 wx.login。
 *
 * 并发去重（关键）：微信 code 是一次性的，同一时刻发起多次 wx.login 会导致
 * 只有第一个 code 有效，其余全部返回 40029。启动时序里 app.js 的静默登录、
 * request.js 的 401 重登、页面主动调用可能同时发生，因此这里做「进程内单飞」：
 * 进行中的登录 Promise 直接复用，结束后无论成败都清空，允许下次重试。
 */
let loginPromise = null;
// 手机号授权前的会话刷新单飞：与静默登录分开，确保即使本地已有 token
// 也会消费新的 wx code，刷新 Redis session_key 后再提交加密数据。
let phoneAuthorizationPromise = null;

function ensureLogin(force) {
  const cached = force ? null : getCachedUser();
  if (cached && cached.token) {
    return Promise.resolve(cached);
  }
  if (loginPromise) {
    return loginPromise;
  }
  loginPromise = wxLogin()
    .then(code => loginWithCode(code))
    .catch(error => {
      clearSession();
      throw error;
    })
    .finally(() => {
      loginPromise = null;
    });
  return loginPromise;
}

/** 当前是否有登录请求进行中（供页面做 loading / 提示判断）。 */
function isLoginPending() {
  return Boolean(loginPromise);
}

/**
 * 手机号授权前强制刷新微信会话。
 *
 * 不复用 ensureLogin 的本地 token 快路径：旧 token 只能证明用户曾经登录，
 * 不能证明 Redis 中仍保存着与本次 encryptedData 匹配的 session_key。
 */
function preparePhoneAuthorization() {
  if (phoneAuthorizationPromise) {
    return phoneAuthorizationPromise;
  }
  phoneAuthorizationPromise = wxLogin()
    .then(code => loginWithCode(code))
    .then(() => {
      const registerContext = getRegisterContext();
      return {
        needRegister: Boolean(
          registerContext && registerContext.registerToken && !isLoggedIn()
        ),
        registerContext
      };
    })
    .finally(() => {
      phoneAuthorizationPromise = null;
    });
  return phoneAuthorizationPromise;
}

/** 判断错误是否属于加密数据对应的微信会话已经失效。 */
function isSessionInvalidError(error) {
  if (!error) return false;
  // 401 类业务码即使没有附带固定中文提示，也属于会话已失效。
  if (error.res && (error.res.code === 8888 || error.res.code === 9999)) return true;
  const message = typeof error === 'string'
    ? error
    : error.message || (error.res && (error.res.message || error.res.msg)) || '';
  return [
    '登录状态已失效，请重新登录',
    '微信数据解密失败',
    '微信授权已失效，请重新登录'
  ].some(fragment => String(message).indexOf(fragment) >= 0);
}

/**
 * 测试用：清空进行中的静默登录单飞 Promise。
 * 生产代码不得调用；仅供 scripts/*.test.mjs 在用例之间复位模块态。
 */
function __resetForTest() {
  loginPromise = null;
  phoneAuthorizationPromise = null;
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
    data: { encryptedData, iv },
    // encryptedData/iv 已绑定当前微信 session_key，禁止请求层自动重登后原样重试。
    retryAuth: false
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

/** 新用户通过微信手机号授权注册并登录（未注册态） */
function registerByPhone(registerToken, encryptedData, iv) {
  return request({
    url: '/api/v1/app/auth/register-by-phone',
    method: 'POST',
    // referrerId：邀请分享进入时暂存的邀请人（后端写 app_user.referrer_id）
    data: { registerToken, encryptedData, iv, referrerId: referrer.getReferrerId() },
    skipAuth: true
  }).then(res => {
    const data = unwrap(res);
    saveSession(data);
    clearRegisterContext();
    // 推荐关系已随注册提交，清除暂存，避免影响该设备后续的其它注册
    referrer.clear();
    return data;
  });
}

/** 新用户通过短信验证码注册并登录（未注册态） */
function registerBySms(registerToken, phone, code) {
  return request({
    url: '/api/v1/app/auth/register-by-sms',
    method: 'POST',
    data: { registerToken, phone, code, referrerId: referrer.getReferrerId() },
    skipAuth: true
  }).then(res => {
    const data = unwrap(res);
    saveSession(data);
    clearRegisterContext();
    referrer.clear();
    return data;
  });
}

module.exports = {
  TOKEN_KEY,
  USER_KEY,
  isLoginPending,
  preparePhoneAuthorization,
  isSessionInvalidError,
  __resetForTest,
  getToken,
  getCachedUser,
  saveSession,
  clearSession,
  isLoggedIn,
  getRegisterContext,
  clearRegisterContext,
  registerByPhone,
  registerBySms,
  wxLogin,
  loginWithCode,
  ensureLogin,
  fetchMe,
  reportLocation,
  bindPhone,
  bindProfile
};
