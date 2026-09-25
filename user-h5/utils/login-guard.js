const auth = require('./auth');
const api = require('./api');
const config = require('../config');
const authState = require('./auth-state');

/**
 * 统一登录/授权拦截器。
 *
 * 分层设计：
 *   1) 静默层 ensureSilentLogin()：wx.login 换 token，用户无感；
 *   2) 登录层 requireLogin()：需要 token 的操作；
 *   3) 手机号层 requirePhone()：交易类操作（下单/领券/兑换/充值/提现）必须绑定手机号。
 *
 * 关键能力：授权完成后自动续跑原操作（pendingAction），用户无需二次点击。
 */

let silentLoginPromise = null;
let pendingAction = null;

/** 提示 */
function toast(message) {
  if (typeof wx !== 'undefined' && wx.showToast) {
    wx.showToast({ title: message, icon: 'none' });
  }
}

/** 静默登录（并发去重） */
function ensureSilentLogin() {
  if (auth.isLoggedIn()) {
    return Promise.resolve(auth.getCachedUser());
  }
  if (silentLoginPromise) {
    return silentLoginPromise;
  }
  silentLoginPromise = auth
    .ensureLogin()
    .catch(error => {
      silentLoginPromise = null;
      throw error;
    })
    .then(user => {
      silentLoginPromise = null;
      return user;
    });
  return silentLoginPromise;
}

/** 当前是否已绑定手机号（统一走 auth-state，避免多处判定不一致） */
function hasPhone() {
  return authState.getAuthState().hasPhone;
}

const AUTH_PAGE = '/pages/auth-login/auth-login';
// 微信小程序页面栈上限为 10
const MAX_PAGE_STACK = 10;

/** 当前页面栈是否已经打开了授权页（避免重复跳转） */
function hasAuthPage() {
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
  return pages.some(page => page && page.route === 'pages/auth-login/auth-login');
}

/**
 * 跳转到独立授权页。
 * pendingAction 保存在本模块内存中，页面跳转不会丢失，授权完成后由
 * flushPendingAction() 续跑原操作。
 */
function openLoginSheet(options) {
  if (typeof wx === 'undefined') return;
  // 已在授权页时不再重复跳转
  if (hasAuthPage()) return;

  const opts = options || {};
  const reason = opts.reason ? '?reason=' + encodeURIComponent(opts.reason) : '';
  const url = AUTH_PAGE + reason;
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];

  if (pages.length >= MAX_PAGE_STACK) {
    // 页面栈已满，navigateTo 会失败，改用 redirectTo 兜底
    wx.redirectTo({
      url,
      fail() {
        toast('请先登录');
      }
    });
    return;
  }

  wx.navigateTo({
    url,
    fail() {
      // 跳转失败时才退化为提示，不静默失败
      toast('请先登录');
    }
  });
}

/**
 * 需要登录才能执行的操作。
 * @param {Function} action 登录成功后要执行的动作（可空）
 * @param {Object} options { reason: 提示语 }
 */
function requireLogin(action, options) {
  const opts = options || {};
  return ensureSilentLogin()
    .then(() => {
      // 未注册用户静默登录后没有 token（后端查无 openid 只下发 registerToken），
      // 不能误判为已登录而直接执行 action，必须先引导注册登录。
      if (!auth.isLoggedIn()) {
        pendingAction = typeof action === 'function' ? action : null;
        openLoginSheet({ phone: false, reason: opts.reason || '请先登录' });
        return null;
      }
      if (typeof action === 'function') return action();
      return null;
    })
    .catch(() => {
      pendingAction = typeof action === 'function' ? action : null;
      openLoginSheet({ phone: false, reason: opts.reason || '请先登录' });
      return null;
    });
}

/**
 * 需要绑定手机号才能执行的操作（交易类）。
 * @param {Function} action 绑定成功后要执行的动作（会自动续跑）
 */
function requirePhone(action, options) {
  const opts = options || {};
  return ensureSilentLogin()
    .then(() => {
      // 未注册用户必须先注册登录（后端查无 openid 时没有 token）
      if (!auth.isLoggedIn()) {
        pendingAction = typeof action === 'function' ? action : null;
        openLoginSheet({ phone: true, reason: '请先登录并绑定手机号' });
        return null;
      }
      if (hasPhone()) {
        return typeof action === 'function' ? action() : null;
      }
      pendingAction = typeof action === 'function' ? action : null;
      openLoginSheet({ phone: true, reason: opts.reason || '该操作需要绑定手机号' });
      return null;
    })
    .catch(() => {
      pendingAction = typeof action === 'function' ? action : null;
      openLoginSheet({ phone: true, reason: '请先登录并绑定手机号' });
      return null;
    });
}

/** 授权成功后由弹层调用：续跑未完成的操作 */
function flushPendingAction() {
  const action = pendingAction;
  pendingAction = null;
  if (typeof action === 'function') {
    return action();
  }
  return null;
}

/** 清空待执行动作（用户放弃授权） */
function clearPendingAction() {
  pendingAction = null;
}

/**
 * 测试用：清空进行中的静默登录单飞 Promise 与待执行动作。
 * 生产代码不得调用；仅供 scripts/*.test.mjs 在用例之间复位模块态。
 */
function __resetForTest() {
  silentLoginPromise = null;
  pendingAction = null;
}

module.exports = {
  ensureSilentLogin,
  requireLogin,
  requirePhone,
  hasPhone,
  openLoginSheet,
  flushPendingAction,
  clearPendingAction,
  __resetForTest,
  toast
};

