const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getUserProfile } = require('../../utils/user-profile');
const loginGuard = require('../../utils/login-guard');

Page(
  withShare({
    data: {
      homeShortcuts: [],
      userProfile: getUserProfile()
    },
    onShow() {
      if (this.getTabBar) this.getTabBar().setData({ selected: 0 });
      // 异步刷新运营配置与用户资料（USE_MOCK=true 时走 mock 回退）
      api
        .fetchHomeConfig()
        .then(cfg => {
          if (cfg && Array.isArray(cfg.shortcuts) && cfg.shortcuts.length) {
            this.setData({ homeShortcuts: cfg.shortcuts });
          }
        })
        .catch(() => null);
      api
        .fetchMe()
        .then(() => this.setData({ userProfile: getUserProfile() }))
        .catch(() => null);
    },
    /** 首页昵称位登录入口：引导登录（不阻塞浏览，授权后自动续跑） */
    handleLoginTap() {
      loginGuard.requireLogin(null, { reason: '登录后可同步会员权益与订单' });
    },
    openCoupons() {
      wx.navigateTo({ url: '/pages/coupon-list/coupon-list' });
    },
    selectOrderMode(event) {
      const { mode } = event.currentTarget.dataset;
      getApp().globalData.orderMode = mode;
      wx.switchTab({ url: '/pages/menu/menu' });
    },
    openJoinApply() {
      wx.navigateTo({ url: '/pages/role-apply/role-apply' });
    },
    handleShortcut(event) {
      const { id, label } = event.currentTarget.dataset;
      if (id === 'stored-value') {
        wx.navigateTo({ url: '/pages/stored-value/stored-value' });
        return;
      }
      if (id === 'points-mall') {
        wx.navigateTo({ url: '/pages/points-mall/points-mall' });
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




