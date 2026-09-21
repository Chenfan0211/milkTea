const { withShare } = require('../../utils/share');
const { profileFunctions } = require('../../data/mock');
const { getUserProfile, maskPhone, saveUserProfile } = require('../../utils/user-profile');
const { getPoints } = require('../../utils/points');
const { buildLevelMeta } = require('../../utils/member-level');
const { getCurrentBusinessRole, getPendingRoles, getDashboard } = require('../../utils/roles');
const loginGuard = require('../../utils/login-guard');
const auth = require('../../utils/auth');
const api = require('../../utils/api');

const initialProfile = getUserProfile();
const initialMeta = buildLevelMeta(initialProfile);

function buildRoleFunctions(roleId) {
  if (!roleId) return [];
  const dashboard = getDashboard(roleId);
  if (!dashboard || !Array.isArray(dashboard.actions)) return [];
  return dashboard.actions.map(item => ({
    id: `role-${item.id}`,
    label: item.label,
    icon: item.icon,
    actionId: item.id,
    roleOnly: true
  }));
}

Page(
  withShare({
    data: {
      profileFunctions,
      roleFunctions: [],
      userProfile: initialProfile,
      businessRole: null,
      pendingRoleCount: 0,
      giftCards: [],
      stats: [
        { id: 'coupon', label: '优惠券', value: initialProfile.couponCount },
        { id: 'balance', label: '储值余额', value: initialProfile.balance },
        { id: 'points', label: '时光币', value: initialProfile.points },
        { id: 'gift', label: '礼品卡', value: 0 }
      ],
      progressPercent: 0,
      loggedIn: false,
      phoneMasked: '',
      remoteUser: null,
      vipLevel: initialMeta.currentName,
      nextLevel: initialMeta.nextName,
      progressCurrent: initialMeta.currentGrowth,
      progressTarget: initialMeta.progressTarget
    },
    onLoad() {
      this.syncLevel();
    },
    onShow() {
      const points = getPoints();
      const app = getApp();
      app.globalData.points = points;
      const userProfile = getUserProfile();
      const businessRole = getCurrentBusinessRole();
      const pendingRoles = getPendingRoles();
      const stats = this.data.stats.map(item => {
        if (item.id === 'points') return Object.assign({}, item, { value: points });
        if (item.id === 'balance') return Object.assign({}, item, { value: userProfile.balance });
        if (item.id === 'coupon') return Object.assign({}, item, { value: userProfile.couponCount });
        if (item.id === 'gift')
          return Object.assign({}, item, { value: userProfile.giftCards.length });
        return item;
      });
      const giftCards = userProfile.giftCards.slice(0, 2);
      this.setData({
        giftCards,
        stats,
        userProfile,
        businessRole,
        pendingRoleCount: pendingRoles.length,
        roleFunctions: buildRoleFunctions(businessRole && businessRole.id)
      });
      this.syncLevel();
      if (this.getTabBar) this.getTabBar().setData({ selected: 4 });

      // 登录态与远端资料（未登录时保持本地展示，不阻塞页面）
      const loggedIn = auth.isLoggedIn();
      const cached = auth.getCachedUser() || {};
      this.setData({
        loggedIn,
        phoneMasked: cached.phone ? maskPhone(cached.phone) : ''
      });
      if (loggedIn) {
        api
          .fetchMe()
          .then(remote => {
            this.setData({ remoteUser: remote, phoneMasked: remote.phone ? maskPhone(remote.phone) : '' });
            // 回写本地资料，供其他页面复用
            saveUserProfile({
              nickname: remote.nickName || userProfile.nickname,
              avatar: remote.avatar || userProfile.avatar,
              phone: remote.phone || userProfile.phone,
              region: userProfile.region
            });
          })
          .catch(() => {});
      }
      if (typeof this.syncUserCard === 'function') this.syncUserCard();

      // 运营配置：我的页功能宫格（后台可编辑）
      api
        .fetchProfileConfig()
        .then(cfg => {
          if (cfg && Array.isArray(cfg.functions) && cfg.functions.length) {
            this.setData({ profileFunctions: cfg.functions });
          }
        })
        .catch(() => null);
    },

    /** 打开授权弹层（供 login-guard 调用） */


    /** 绑定手机号入口 */
    handleBindPhone() {
      loginGuard.requirePhone(null, { reason: '绑定手机号后可下单与领取优惠券' });
    },
    syncLevel() {
      const meta = buildLevelMeta(getUserProfile());
      this.setData({
        vipLevel: meta.currentName,
        nextLevel: meta.nextName,
        progressCurrent: meta.currentGrowth,
        progressTarget: meta.progressTarget,
        progressPercent: meta.progressPercent
      });
    },
    /** 进入个人资料页：未登录先引导登录（登录后自动续跑） */
    openProfileData() {
      loginGuard.requireLogin(
        () => {
          wx.navigateTo({ url: '/pages/profile-data/profile-data' });
        },
        { reason: '登录后可查看和编辑个人资料' }
      );
    },
    openGiftCards() {
      wx.navigateTo({ url: '/pages/gift-card/gift-card' });
    },
    openGiftCardOrder(event) {
      const { id } = event.currentTarget.dataset;
      if (!id) return;
      wx.navigateTo({ url: '/pages/order-detail/order-detail?id=' + id });
    },
    openMemberRights() {
      wx.navigateTo({ url: '/pages/member-rights/member-rights' });
    },
    openMenu() {
      wx.switchTab({ url: '/pages/menu/menu' });
    },
    openRoleCenter() {
      wx.navigateTo({ url: '/pages/role-center/role-center' });
    },
    openRoleApply() {
      wx.navigateTo({ url: '/pages/role-apply/role-apply' });
    },
    openRoleFunction(event) {
      const { action } = event.currentTarget.dataset;
      const roleId = this.data.businessRole && this.data.businessRole.id;
      if (!roleId || !action) return;
      const routeMap = {
        verify: '/pages/role-verify/role-verify',
        products: '/pages/role-products/role-products',
        invest: '/pages/role-invest/role-invest',
        orders: '/pages/resource-orders/resource-orders',
        income: '/pages/role-income/role-income',
        withdraw: '/pages/role-withdraw/role-withdraw'
      };
      const url = routeMap[action];
      if (!url) {
        wx.showToast({ title: '该功能待接入', icon: 'none' });
        return;
      }
      wx.navigateTo({ url });
    },
    handleProfileAction(event) {
      const { id, label } = event.currentTarget.dataset;
      if (id === 'balance') {
        wx.navigateTo({ url: '/pages/stored-value/stored-value' });
        return;
      }
      if (id === 'coupon' || id === 'coupon-wallet') {
        wx.navigateTo({ url: '/pages/coupon-list/coupon-list' });
        return;
      }
      if (id === 'gift') {
        this.openGiftCards();
        return;
      }
      if (id === 'points') {
        wx.navigateTo({ url: '/pages/points-mall/points-mall' });
        return;
      }
      if (id === 'benefits') {
        wx.navigateTo({ url: '/pages/member-rights/member-rights' });
        return;
      }
      if (id === 'share') {
        wx.navigateTo({ url: '/pages/share-referral/share-referral' });
        return;
      }
      if (id === 'cooperation') {
        this.openRoleApply();
        return;
      }
      if (id === 'service') {
        wx.navigateTo({ url: '/pages/service/service' });
        return;
      }
      wx.showToast({ title: `${label || '功能'}暂未接入`, icon: 'none' });
    },
    showUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);




