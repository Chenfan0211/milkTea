const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getUserProfile } = require('../../utils/user-profile');
const { getUsableCouponCount, refreshCouponsFromRemote } = require('../../utils/coupons');
const loginGuard = require('../../utils/login-guard');
const authState = require('../../utils/auth-state');
const featurePolicy = require('../../utils/feature-policy');
const homeHero = require('../../utils/home-hero');
const bannerTarget = require('../../utils/banner-target');

const DEFAULT_HOME_HERO = '/assets/images/3x/home-hero.jpg';
const DEFAULT_HOME_HERO_ALT = '金秋入茶限定上新';

Page(
  withShare({
    data: {
      homeShortcuts: [],
      homeHeroUrl: DEFAULT_HOME_HERO,
      homeHeroAlt: DEFAULT_HOME_HERO_ALT,
      homeBanners: homeHero.DEFAULT_HOME_BANNERS.map(item => Object.assign({}, item)),
      userProfile: getUserProfile(),
      // 可用优惠券数（首页「优惠券N张」）；null 表示未登录，不展示入口
      couponCount: null
    },
    onShow() {
      if (this.getTabBar)
        this.getTabBar().setData({ selected: featurePolicy.getTabBarSelectedIndex('/pages/home/home') });
      // 首页配置与用户资料是两个独立数据源，并行请求：
      // 串行会让首屏等待「配置 + 资料 + 券包」三者相加，并行后只需等最慢的一条。
      const configTask = api
        .fetchHomeConfig()
        .then(cfg => {
          const banners = homeHero.normalizeHomeBanners(cfg && cfg.hero, api.resolveAssetUrl);
          const updates = {
            homeHeroUrl: banners[0].imageUrl,
            homeHeroAlt: banners[0].altText,
            homeBanners: banners
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
    handleHeroError(event) {
      const index = Number(
        event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.index
      );
      const banners = homeHero.removeFailedHomeBanner(this.data.homeBanners, Number.isFinite(index) ? index : 0);
      this.setData({ homeBanners: banners, homeHeroUrl: banners[0].imageUrl, homeHeroAlt: banners[0].altText });
    },
    openHomeBanner(event) {
      const index = Number(
        event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.index
      );
      const item = this.data.homeBanners[index];
      if (item) return bannerTarget.openBannerTarget(item);
      return null;
    },

    /** 首页昵称位登录入口：跳转独立登录页（用户主动点击，符合「先浏览后登录」的合规要求） */
    handleLoginTap() {
      loginGuard.openLoginSheet({ reason: '登录后可同步会员权益与订单' });
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
      // 选择消费方式只进入公开菜单，登录留到下单等账户操作时申请。
      getApp().globalData.orderMode = mode;
      wx.switchTab({ url: '/pages/menu/menu' });
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
