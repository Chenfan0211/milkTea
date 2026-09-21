const { withShare } = require('../../utils/share');
const { homeShortcuts, userProfile } = require('../../data/mock');
const api = require('../../utils/api');

Page(
  withShare({
    data: {
      homeShortcuts,
      userProfile
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
      api.fetchMe().catch(() => null);
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



