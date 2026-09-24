const guard = require('./login-guard');
const auth = require('./auth');
const authState = require('./auth-state');
const { refreshUserProfileFromRemote } = require('./user-profile');

/**
 * 入口层：让用户「进入小程序即完成微信登录」。
 *
 * 定位：在既有三层（静默 / 登录 / 手机号）之上补一层「入口编排」，
 * 只负责两件事：
 *   1) 冷启动时把静默登录跑完，并带超时兜底（失败也必须放行，不能卡启动页）；
 *   2) 决定是否弹出「绑手机号」引导，并保证同一会话 + 冷却期内只引导一次。
 *
 * 合规边界：getPhoneNumber 只能由用户点击触发，本模块绝不程序化调起授权，
 * 只做跳转到授权页的编排；授权页保留「暂不登录」。
 */

const ENTRY_PROMPT_KEY = 'milkTea:auth:entry-prompted-at';
// 引导冷却期：12 小时内不重复打扰（用户明确拒绝过就更不该反复弹）
const ENTRY_PROMPT_COOLDOWN = 12 * 60 * 60 * 1000;
// 静默登录超时：超过即放行，宁可未登录也不能卡住启动
const ENTRY_LOGIN_TIMEOUT = 1500;
const HOME_PATH = '/pages/home/home';

// 允许从入口直接还原到具体页的来源（其余一律回首页，避免任意跳转）。
// key 为 app.json 里的完整页面路径，value 为该页允许透传的参数白名单。
const ENTRY_TARGET_WHITELIST = {
  'pages/coupon-stores/coupon-stores': ['couponId'],
  'pages/coupon-products/coupon-products': ['couponId', 'storeId'],
  'pages/points-exchange/points-exchange': ['id'],
  'pages/gift-card-purchase/gift-card-purchase': ['id']
};

let promptEnabled = true;
let promptedThisSession = false;

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
    // 存储失败不影响主流程
  }
}

/** 是否允许引导（合规总开关，测试与灰度可关） */
function setPromptEnabled(enabled) {
  promptEnabled = Boolean(enabled);
}

function isPromptEnabled() {
  return promptEnabled;
}

/** 测试用：清空会话标记与内存态（不删冷却时间戳，由 storage.clear 控制） */
function __resetForTest() {
  promptedThisSession = false;
}

/**
 * 本次是否应该弹绑手机号引导。
 * 三重去重：开关关闭 / 本次会话已引导 / 冷却期内已引导。
 */
function shouldPromptEntry(now = Date.now()) {
  if (!promptEnabled) return false;
  if (promptedThisSession) return false;
  const last = Number(readStorage(ENTRY_PROMPT_KEY)) || 0;
  if (last > 0 && now - last < ENTRY_PROMPT_COOLDOWN) return false;
  return true;
}

/** 标记已引导（写入冷却时间戳 + 会话标记） */
function markEntryPrompted(now = Date.now()) {
  promptedThisSession = true;
  writeStorage(ENTRY_PROMPT_KEY, now);
}

/**
 * 入口静默登录：跑完 wx.login 换 token，并保证在 timeout 内返回。
 * 永不 reject —— 调用方（启动页）必须能无条件继续跳转。
 */
function ensureEntryLogin(options = {}) {
  const timeout = Number(options.timeout) > 0 ? Number(options.timeout) : ENTRY_LOGIN_TIMEOUT;

  return new Promise(resolve => {
    let settled = false;
    const finish = payload => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(payload);
    };
    const timer = setTimeout(() => {
      finish({ ok: false, state: authState.getAuthState(), timedOut: true });
    }, timeout);

    guard
      .ensureSilentLogin()
      .then(() => refreshUserProfileFromRemote())
      .then(() => finish({ ok: true, state: authState.getAuthState(), timedOut: false }))
      .catch(error => finish({ ok: false, state: authState.getAuthState(), timedOut: false, error }));
  });
}

/**
 * 还原入口目标页。
 * 只认白名单内的来源页面，其余（含未识别的 scene）一律回首页，
 * 避免被构造参数跳到任意页面。
 */
function resolveEntryTarget(options = {}) {
  const opts = options || {};
  const from = opts.from ? decodeURIComponent(String(opts.from)) : '';
  const allowed = ENTRY_TARGET_WHITELIST[from];
  if (!allowed) return HOME_PATH;

  const rawQuery = opts.query ? decodeURIComponent(String(opts.query)) : '';
  if (!rawQuery) return `/${from}`;
  const params = rawQuery
    .split('&')
    .map(pair => pair.split('='))
    .filter(pair => allowed.indexOf(pair[0]) !== -1 && pair[1])
    .map(pair => `${pair[0]}=${pair[1]}`);
  return params.length ? `/${from}?${params.join('&')}` : `/${from}`;
}

/** 页面跳启动页时携带上下文用 */
function buildLaunchQuery(options = {}) {
  const opts = options || {};
  const parts = [];
  if (opts.from) parts.push(`from=${encodeURIComponent(String(opts.from))}`);
  if (opts.query) parts.push(`query=${encodeURIComponent(String(opts.query))}`);
  return parts.join('&');
}

module.exports = {
  ENTRY_PROMPT_KEY,
  ENTRY_PROMPT_COOLDOWN,
  ENTRY_LOGIN_TIMEOUT,
  HOME_PATH,
  ENTRY_TARGET_WHITELIST,
  setPromptEnabled,
  isPromptEnabled,
  __resetForTest,
  shouldPromptEntry,
  markEntryPrompted,
  ensureEntryLogin,
  resolveEntryTarget,
  buildLaunchQuery
};

