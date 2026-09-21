const DEFAULT_SHARE_IMAGE = '/assets/images/3x/share-home.jpg';
const DEFAULT_SHARE_TITLE = '五零时光新中式养生茶饮';
const SHARE_SCENE_KEY = 'shareScene';
const SHARE_SCENE_TIMELINE = 'timeline';
const HOME_PATH = '/pages/home/home';

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
  'pages/role-center/role-center': '五零时光角色中心',
  'pages/role-workbench/role-workbench': '五零时光角色工作台',
  'pages/role-apply/role-apply': '五零时光加盟合作',
  'pages/role-verify/role-verify': '五零时光核销订单',
  'pages/role-products/role-products': '五零时光门店选品',
  'pages/role-product-detail/role-product-detail': '五零时光商品详情',
  'pages/role-income/role-income': '五零时光经营收益',
  'pages/role-invest/role-invest': '五零时光点位投资申请',
  'pages/role-invest-apply/role-invest-apply': '五零时光投资申请',
  'pages/role-invest-records/role-invest-records': '五零时光投资申请记录',
  'pages/role-invest-detail/role-invest-detail': '五零时光投资申请详情',
  'pages/role-income-records/role-income-records': '五零时光收益记录',
  'pages/role-income-detail/role-income-detail': '五零时光收益详情',
  'pages/role-income-rules/role-income-rules': '五零时光结算说明',
  'pages/role-withdraw/role-withdraw': '五零时光提现',
  'pages/role-withdraw-records/role-withdraw-records': '五零时光提现记录',
  'pages/role-withdraw-rules/role-withdraw-rules': '五零时光提现规则',
  'pages/role-withdraw-detail/role-withdraw-detail': '五零时光提现详情',
  'pages/share-referral/share-referral': '五零时光分享有礼'
};

const PRIVATE_PAGES = new Set([
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
  'pages/role-center/role-center',
  'pages/role-workbench/role-workbench',
  'pages/role-apply/role-apply',
  'pages/role-verify/role-verify',
  'pages/role-products/role-products',
  'pages/role-product-detail/role-product-detail',
  'pages/role-income/role-income',
  'pages/role-invest/role-invest',
  'pages/role-invest-apply/role-invest-apply',
  'pages/role-invest-records/role-invest-records',
  'pages/role-invest-detail/role-invest-detail',
  'pages/role-income-records/role-income-records',
  'pages/role-income-detail/role-income-detail',
  'pages/role-income-rules/role-income-rules',
  'pages/role-withdraw/role-withdraw',
  'pages/role-withdraw-records/role-withdraw-records',
  'pages/role-withdraw-rules/role-withdraw-rules',
  'pages/role-withdraw-detail/role-withdraw-detail',
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
  const path = isPrivatePage(normalizedRoute) ? HOME_PATH : `/${normalizedRoute}`;
  const query = isPrivatePage(normalizedRoute) ? '' : buildQuery(normalizedRoute, options);
  return {
    title: getShareTitle(normalizedRoute, config.title),
    path: query ? `${path}?${query}` : path,
    imageUrl: config.imageUrl || DEFAULT_SHARE_IMAGE
  };
}

function buildShareTimeline(route, options, config = {}) {
  const normalizedRoute = normalizeRoute(route);
  const query = isPrivatePage(normalizedRoute)
    ? `${SHARE_SCENE_KEY}=${SHARE_SCENE_TIMELINE}`
    : buildQuery(normalizedRoute, options);
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
