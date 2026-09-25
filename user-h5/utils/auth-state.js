const auth = require('./auth');

/**
 * 登录态唯一判定入口。
 *
 * 背景：项目里同时存在「有 token」与「绑了手机号」两种判断，
 * 导致同一用户在我的页显示已登录、下单时却被要求登录。
 * 这里把两者统一为三种明确的等级，业务代码一律通过本模块查询。
 *
 *   anonymous  —— 无 token，未登录（只能浏览）
 *   authorized —— 有 token 但未绑定手机号（可浏览，不可交易）
 *   full       —— 有 token 且已绑定手机号（可正常交易）
 */

const LEVEL = {
  ANONYMOUS: 'anonymous',
  AUTHORIZED: 'authorized',
  FULL: 'full'
};

const LABEL = {
  anonymous: '未登录',
  authorized: '已登录 · 未绑定手机号',
  full: '已登录'
};

/** 读取手机号：优先用户缓存，回落到本地资料缓存 */
function readPhone() {
  const cached = auth.getCachedUser() || {};
  if (cached.phone) return String(cached.phone);
  try {
    const profile = require('./user-profile').getUserProfile() || {};
    return profile.phone ? String(profile.phone) : '';
  } catch (error) {
    return '';
  }
}

/**
 * 当前登录态。
 * @returns {{ level: string, label: string, hasToken: boolean, hasPhone: boolean, phone: string }}
 */
function getAuthState() {
  const hasToken = Boolean(auth.getToken());
  if (!hasToken) {
    return { level: LEVEL.ANONYMOUS, label: LABEL.anonymous, hasToken: false, hasPhone: false, phone: '' };
  }
  const phone = readPhone();
  const hasPhone = Boolean(phone);
  const level = hasPhone ? LEVEL.FULL : LEVEL.AUTHORIZED;
  return { level, label: LABEL[level], hasToken: true, hasPhone, phone };
}

/** 是否已登录（有 token 即可，不要求绑定手机号） */
function isLoggedIn() {
  return Boolean(auth.getToken());
}

/** 是否可交易（已登录且已绑定手机号） */
function canTrade() {
  return getAuthState().level === LEVEL.FULL;
}

/**
 * 登录态可读文案，供页面直接渲染。
 * 未绑手机号时附带引导语，避免用户误以为「登录失败」。
 */
function describeAuthState() {
  const state = getAuthState();
  return {
    ...state,
    tip: state.level === LEVEL.ANONYMOUS
      ? '登录后可下单、领券与查看订单'
      : state.level === LEVEL.AUTHORIZED
        ? '绑定手机号后即可下单与领取优惠券'
        : ''
  };
}

module.exports = {
  LEVEL,
  LABEL,
  getAuthState,
  isLoggedIn,
  canTrade,
  describeAuthState
};
