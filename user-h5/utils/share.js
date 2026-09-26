const DEFAULT_SHARE_IMAGE = '/assets/images/3x/share-home.jpg';
const DEFAULT_SHARE_TITLE = '五零时光新中式养生茶饮';
const SHARE_SCENE_KEY = 'shareScene';
const SHARE_SCENE_TIMELINE = 'timeline';
const HOME_PATH = '/pages/home/home';
/**
 * 邀请分享参数名。
 *
 * 为什么单独定义：邀请分享必须携带「邀请人 userId」以便好友注册时绑定推荐关系
 * （后端落库 app_user.referrer_id）。参数名在这里与启动页/注册链路共用，
 * 避免各处手写字符串导致拼错或改名时漏改。
 */
const REFERRER_PARAM = 'referrerId';

const PAGE_SHARE_TITLES = {
  'pages/home/home': '五零时光新中式养生茶饮',
  'pages/menu/menu': '五零时光点单',
  'pages/member/member': '五零时光会员专区',
  'pages/member-level-rules/member-level-rules': '五零时光会员等级说明',
  'pages/orders/orders': '五零时光订单',
  'pages/profile/profile': '五零时光会员中心',
  'pages/profile-data/profile-data': '五零时光个人资料',
  'pages/stored-value/stored-value': '五零时光会员储值｜充值享优惠',
  'pages/activity-rules/activity-rules': '五零时光会员日活动规则',
  'pages/favorite-stores/favorite-stores': '五零时光收藏门店',
  'pages/order-detail/order-detail': '五零时光订单详情',
  'pages/coupon-list/coupon-list': '五零时光优惠券',
  'pages/points-mall/points-mall': '五零时光时光币商城',
  'pages/points-signin/points-signin': '五零时光签到赢好礼',
  'pages/points-signin-rules/points-signin-rules': '五零时光签到活动规则',
  'pages/points-exchange/points-exchange': '五零时光时光币兑换',
  'pages/points-detail/points-detail': '五零时光时光币明细',
  'pages/exchange-records/exchange-records': '五零时光兑换记录',
  'pages/gift-card/gift-card': '五零时光礼品卡',
  'pages/gift-card-purchase/gift-card-purchase': '五零时光礼品卡',
  'pages/order-confirm/order-confirm': '五零时光点单',
  'pages/coupon-stores/coupon-stores': '五零时光门店',
  'pages/coupon-products/coupon-products': '五零时光适用商品',
  'pages/city-picker/city-picker': '五零时光门店',
  'pages/member-rights/member-rights': '五零时光会员权益',
  'packageRole/role-center/role-center': '五零时光角色中心',
  'packageRole/role-workbench/role-workbench': '五零时光角色工作台',
  'packageRole/role-apply/role-apply': '五零时光加盟合作',
  'packageRole/role-verify/role-verify': '五零时光核销订单',
  'packageRole/role-products/role-products': '五零时光门店选品',
  'packageRole/role-product-detail/role-product-detail': '五零时光商品详情',
  'packageRole/role-income/role-income': '五零时光经营收益',
  'packageRole/role-invest/role-invest': '五零时光点位投资申请',
  'packageRole/role-invest-apply/role-invest-apply': '五零时光投资申请',
  'packageRole/role-invest-records/role-invest-records': '五零时光投资申请记录',
  'packageRole/role-invest-detail/role-invest-detail': '五零时光投资申请详情',
  'packageRole/role-income-records/role-income-records': '五零时光收益记录',
  'packageRole/role-income-detail/role-income-detail': '五零时光收益详情',
  'packageRole/role-income-rules/role-income-rules': '五零时光结算说明',
  'packageRole/role-withdraw/role-withdraw': '五零时光提现',
  'packageRole/role-withdraw-records/role-withdraw-records': '五零时光提现记录',
  'packageRole/role-withdraw-rules/role-withdraw-rules': '五零时光提现规则',
  'packageRole/role-withdraw-detail/role-withdraw-detail': '五零时光提现详情',
  'pages/share-referral/share-referral': '五零时光分享有礼'
};

const PRIVATE_PAGES = new Set([
  'pages/launch/launch',
  'pages/auth-login/auth-login',
  'pages/legal/legal',
  'pages/service/service',
  'pages/stored-value/stored-value',
  'pages/favorite-stores/favorite-stores',
  'pages/profile/profile',
  'pages/profile-data/profile-data',
  'pages/orders/orders',
  'pages/order-detail/order-detail',
  'pages/order-confirm/order-confirm',
  'pages/points-detail/points-detail',
  'pages/exchange-records/exchange-records',
  'pages/member-rights/member-rights',
  'packageRole/role-center/role-center',
  'packageRole/role-workbench/role-workbench',
  'packageRole/role-apply/role-apply',
  'packageRole/role-verify/role-verify',
  'packageRole/role-products/role-products',
  'packageRole/role-product-detail/role-product-detail',
  'packageRole/role-income/role-income',
  'packageRole/role-invest/role-invest',
  'packageRole/role-invest-apply/role-invest-apply',
  'packageRole/role-invest-records/role-invest-records',
  'packageRole/role-invest-detail/role-invest-detail',
  'packageRole/role-income-records/role-income-records',
  'packageRole/role-income-detail/role-income-detail',
  'packageRole/role-income-rules/role-income-rules',
  'packageRole/role-withdraw/role-withdraw',
  'packageRole/role-withdraw-records/role-withdraw-records',
  'packageRole/role-withdraw-rules/role-withdraw-rules',
  'packageRole/role-withdraw-detail/role-withdraw-detail',
  'pages/share-referral/share-referral'
]);

const SAFE_SHARE_PARAMS = {
  'pages/points-exchange/points-exchange': ['id'],
  'pages/gift-card-purchase/gift-card-purchase': ['id'],
  'pages/coupon-stores/coupon-stores': ['couponId'],
  'pages/coupon-products/coupon-products': ['couponId', 'storeId'],
  'pages/city-picker/city-picker': ['city']
};

function normalizeRoute(route) {
  return String(route || '').replace(/^\//, '');
}

function getInstanceRoute(instance) {
  const directRoute = normalizeRoute(instance && instance.route);
  if (directRoute) return directRoute;
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
  const current = pages[pages.length - 1];
  return normalizeRoute(current && current.route);
}

function getShareTitle(route, overrideTitle) {
  return overrideTitle || PAGE_SHARE_TITLES[normalizeRoute(route)] || DEFAULT_SHARE_TITLE;
}

function isPrivatePage(route) {
  return PRIVATE_PAGES.has(normalizeRoute(route));
}

function buildQuery(route, options) {
  const safeKeys = SAFE_SHARE_PARAMS[normalizeRoute(route)] || [];
  const source = options && typeof options === 'object' ? options : {};
  return safeKeys
    .filter(key => source[key] !== undefined && source[key] !== null && source[key] !== '')
    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(String(source[key]))}`)
    .join('&');
}

function buildShareAppMessage(route, options, config = {}) {
  const normalizedRoute = normalizeRoute(route);
  const isPrivate = isPrivatePage(normalizedRoute);
  const path = isPrivate ? HOME_PATH : `/${normalizedRoute}`;
  let query = isPrivate ? '' : buildQuery(normalizedRoute, options);

  // 邀请分享：私密页（如「分享有礼」）回落首页时，仍需带上邀请人 userId。
  // 历史问题：私密页的 query 会被整体清空，导致分享链接既不带参数、
  // 好友注册也无法绑定推荐关系（app_user.referrer_id 永远为空）。
  // 这里只在显式传入 inviteReferrer 时追加该参数，不影响其它私密页的分享行为。
  if (config.inviteReferrer) {
    const pair = `${REFERRER_PARAM}=${encodeURIComponent(String(config.inviteReferrer))}`;
    query = query ? `${query}&${pair}` : pair;
  }

  return {
    title: getShareTitle(normalizedRoute, config.title),
    path: query ? `${path}?${query}` : path,
    imageUrl: config.imageUrl || DEFAULT_SHARE_IMAGE
  };
}

function buildShareTimeline(route, options, config = {}) {
  const normalizedRoute = normalizeRoute(route);
  let query = isPrivatePage(normalizedRoute)
    ? `${SHARE_SCENE_KEY}=${SHARE_SCENE_TIMELINE}`
    : buildQuery(normalizedRoute, options);

  // 朋友圈分享同样需要携带邀请人（好友从朋友圈进入也要能绑定推荐关系）
  if (config.inviteReferrer) {
    const pair = `${REFERRER_PARAM}=${encodeURIComponent(String(config.inviteReferrer))}`;
    query = query ? `${query}&${pair}` : pair;
  }
  return {
    title: getShareTitle(normalizedRoute, config.title),
    query,
    imageUrl: config.imageUrl || DEFAULT_SHARE_IMAGE
  };
}

function withShare(pageOptions, config = {}) {
  const originalOnLoad = pageOptions.onLoad;

  pageOptions.onLoad = function onLoad(options = {}) {
    this.__shareOptions__ = options || {};
    try {
      if (typeof wx !== 'undefined' && wx.showShareMenu) {
        wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] });
      }
    } catch (error) {
      // Share menu support differs across base library versions; sharing hooks remain available.
    }

    const route = getInstanceRoute(this);
    if (isPrivatePage(route) && this.__shareOptions__[SHARE_SCENE_KEY] === SHARE_SCENE_TIMELINE) {
      wx.reLaunch({ url: HOME_PATH });
      return;
    }
    if (originalOnLoad) return originalOnLoad.call(this, options);
  };

  pageOptions.onShareAppMessage = function onShareAppMessage() {
    return buildShareAppMessage(getInstanceRoute(this), this.__shareOptions__, config);
  };

  pageOptions.onShareTimeline = function onShareTimeline() {
    return buildShareTimeline(getInstanceRoute(this), this.__shareOptions__, config);
  };

  return pageOptions;
}

module.exports = {
  DEFAULT_SHARE_IMAGE,
  REFERRER_PARAM,
  DEFAULT_SHARE_TITLE,
  PAGE_SHARE_TITLES,
  PRIVATE_PAGES,
  SAFE_SHARE_PARAMS,
  buildShareAppMessage,
  buildShareTimeline,
  getShareTitle,
  isPrivatePage,
  withShare
};

