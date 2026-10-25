const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getUserProfile } = require('../../utils/user-profile');
const { getUsableCouponCount, refreshCouponsFromRemote } = require('../../utils/coupons');
const loginGuard = require('../../utils/login-guard');
const authState = require('../../utils/auth-state');
const featurePolicy = require('../../utils/feature-policy');

const DEFAULT_HOME_HERO = '/assets/images/3x/home-hero.jpg';
const DEFAULT_HOME_HERO_ALT = '金秋入茶限定上新';

function normalizeHomeHero(config) {
  const hero = config && config.hero && typeof config.hero === 'object' ? config.hero : {};
  const rawUrl = typeof hero.imageUrl === 'string' ? hero.imageUrl.trim() : '';
  const rawAlt = typeof hero.altText === 'string' ? hero.altText.trim() : '';
  return {
    url: rawUrl ? api.resolveAssetUrl(rawUrl) : DEFAULT_HOME_HERO,
    altText: rawAlt || DEFAULT_HOME_HERO_ALT
  };
}

Page(
  withShare({
    data: {
      homeShortcuts: [],
      homeHeroUrl: DEFAULT_HOME_HERO,
      homeHeroAlt: DEFAULT_HOME_HERO_ALT,
      userProfile: getUserProfile(),
      // 可用优惠券数（首页「优惠券N张」）；null 表示未登录，不展示入口
      couponCount: null,
      // 登录弹层（图 1）：首页「点击登录」唤起，不再跳独立授权页
      loginSheetVisible: false
    },
    onShow() {
      if (this.getTabBar)
        this.getTabBar().setData({ selected: featurePolicy.getTabBarSelectedIndex('/pages/home/home') });
      // 首页配置与用户资料是两个独立数据源，并行请求：
      // 串行会让首屏等待「配置 + 资料 + 券包」三者相加，并行后只需等最慢的一条。
      const configTask = api
        .fetchHomeConfig()
        .then(cfg => {
          const hero = normalizeHomeHero(cfg);
          const updates = {
            homeHeroUrl: hero.url,
            homeHeroAlt: hero.altText
          };
          if (cfg && Array.isArray(cfg.shortcuts) && cfg.shortcuts.length) {
            updates.homeShortcuts = cfg.shortcuts;
          }
          this.setData(updates);
        })
        .catch(() => null);
      // 未登录：不请求需要登录的 /me（否则 401 会触发全局「重新登录」引导，
      // 造成「一进首页就强制登录」，也不符合「先浏览后授权」的合规要求）。
      // 直接用本地默认资料渲染，昵称位显示「请登录」，由用户主动点击后再引导。
      if (!authState.isLoggedIn()) {
        this.setData({ userProfile: getUserProfile(), couponCount: null });
        return configTask;
      }
      const profileTask = api
        .fetchMe()
        .then(() => {
          this.setData({ userProfile: getUserProfile() });
          // 登录态下再拉券包并更新可用券数量；未登录时 fetchMe 失败走 catch
          return refreshCouponsFromRemote().then(() => this.setData({ couponCount: getUsableCouponCount() }));
        })
        .catch(() => this.setData({ couponCount: null }));
      return Promise.all([configTask, profileTask]);
    },
    handleHeroError() {
      if (this.data.homeHeroUrl !== DEFAULT_HOME_HERO) {
        this.setData({ homeHeroUrl: DEFAULT_HOME_HERO, homeHeroAlt: DEFAULT_HOME_HERO_ALT });
      }
    },

    /** 首页昵称位登录入口：跳转独立登录页（用户主动点击，符合「先浏览后登录」的合规要求） */
    handleLoginTap() {
      loginGuard.openLoginSheet({ reason: '登录后可同步会员权益与订单' });
    },
    closeLoginSheet() {
      this.setData({ loginSheetVisible: false });
    },
    /** 弹层内「暂时跳过」：仅关闭弹层，可继续浏览公开内容 */
    handleLoginSkip() {
      this.setData({ loginSheetVisible: false });
      loginGuard.clearPendingAction();
    },
    /** 弹层内授权成功：关闭弹层、刷新资料，并续跑被拦截的操作 */
    handleLoginSuccess() {
      this.setData({ loginSheetVisible: false });
      api
        .fetchMe(true)
        .then(() => this.setData({ userProfile: getUserProfile() }))
        .catch(() => null);
      loginGuard.flushPendingAction();
    },
    handleLoginFail() {
      loginGuard.toast('登录失败，请稍后重试');
    },
    openCoupons() {
      loginGuard.requireLogin(
        () => {
          wx.navigateTo({ url: '/pages/coupon-list/coupon-list' });
        },
        { reason: '登录后可查看优惠券' }
      );
    },
    selectOrderMode(event) {
      const { mode } = event.currentTarget.dataset;
      loginGuard.requireLogin(
        () => {
          getApp().globalData.orderMode = mode;
          wx.switchTab({ url: '/pages/menu/menu' });
        },
        { reason: '登录后即可点单' }
      );
    },
    openJoinApply() {
      wx.navigateTo({ url: '/packageRole/role-apply/role-apply' });
    },
    handleShortcut(event) {
      const { id, label } = event.currentTarget.dataset;
      if (id === 'stored-value') {
        loginGuard.requireLogin(
          () => {
            wx.navigateTo({ url: '/pages/stored-value/stored-value' });
          },
          { reason: '登录后即可储值' }
        );
        return;
      }
      if (id === 'points-mall') {
        loginGuard.requireLogin(
          () => {
            wx.navigateTo({ url: '/pages/points-mall/points-mall' });
          },
          { reason: '登录后即可兑换时光币好礼' }
        );
        return;
      }
      // 客服入口：与「我的」页 service 入口跳转保持一致（同一路径、同样不做登录拦截）。
      // 客服页是匿名可用的兜底联系渠道，强制登录反而会把用户挡在门外。
      if (id === 'service') {
        wx.navigateTo({ url: '/pages/service/service' });
        return;
      }
      this.showUnavailable({ currentTarget: { dataset: { label } } });
    },
    showUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);
