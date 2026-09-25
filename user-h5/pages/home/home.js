const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getUserProfile } = require('../../utils/user-profile');
const loginGuard = require('../../utils/login-guard');

Page(
  withShare({
    data: {
      homeShortcuts: [],
      userProfile: getUserProfile(),
      // 登录弹层（图 1）：首页「点击登录」唤起，不再跳独立授权页
      loginSheetVisible: false
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
    /** 首页昵称位登录入口：页内唤起登录弹层（不跳页） */
    handleLoginTap() {
      this.setData({ loginSheetVisible: true });
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