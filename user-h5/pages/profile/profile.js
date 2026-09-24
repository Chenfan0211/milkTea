const { withShare } = require('../../utils/share');
const { DEFAULT_AVATAR, getUserProfile, maskPhone, saveUserProfile, refreshUserProfileFromRemote } = require('../../utils/user-profile');
const { getPoints } = require('../../utils/points');
const { buildLevelMeta, refreshMemberLevelsFromRemote } = require('../../utils/member-level');
const { getCurrentBusinessRole, getPendingRoles, getDashboard } = require('../../utils/roles');
const loginGuard = require('../../utils/login-guard');
const auth = require('../../utils/auth');
const authState = require('../../utils/auth-state');
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
      profileFunctions: [],
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
      progressTarget: initialMeta.progressTarget,
      // 未授权头像时的默认头像（避免空白）
      defaultAvatar: DEFAULT_AVATAR
    },
    onLoad() {
      this.syncLevel();
      // 会员等级来自接口：拉取后重建等级卡（否则等级名与进度条为空）
      refreshMemberLevelsFromRemote().then(() => this.syncLevel());
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
      refreshMemberLevelsFromRemote().then(() => this.syncLevel());
      if (this.getTabBar) this.getTabBar().setData({ selected: 4 });

      // 登录态与远端资料（三态：未登录 / 已登录未绑手机号 / 可交易）
      const state = authState.getAuthState();
      const cached = auth.getCachedUser() || {};
      this.setData({
        loggedIn: state.hasToken,
        authStateLevel: state.level,
        authLabel: state.label,
        loginFailed: Boolean(app && app.globalData && app.globalData.loginFailed),
        phoneMasked: state.phone ? maskPhone(state.phone) : ''
      });
      if (state.hasToken) {
        api
          .fetchMe()
          .then(remote => {
            this.setData({ remoteUser: remote, phoneMasked: remote.phone ? maskPhone(remote.phone) : '' });
            // 回写本地资料，供其他页面复用
            saveUserProfile({
              nickname: remote.nickName || userProfile.nickname,
              // 未授权头像时回落到默认头像，避免头像区空白
              avatar: remote.avatar || userProfile.avatar || DEFAULT_AVATAR,
              phone: remote.phone || userProfile.phone,
              region: userProfile.region
            });
            this.setData({ userProfile: getUserProfile() });
          })
          .catch(() => {});
        // 我的礼品卡：来自后端 /api/v1/app/gift-cards（当前用户名下的卡）
        // 卡实体只有 denomination_id，需要关联面额接口补 name / image
        Promise.all([api.fetchMyGiftCards(), api.fetchGiftCardDenominations()])
          .then(([cards, denominations]) => {
            const cardList = Array.isArray(cards) ? cards : [];
            const denomMap = {};
            (Array.isArray(denominations) ? denominations : []).forEach(d => {
              denomMap[d.id] = d;
            });
            const decorated = cardList.map(card => {
              const denom = denomMap[card.denominationId] || {};
              return Object.assign({}, card, {
                name: denom.cardName || denom.name || '礼品卡',
                image: denom.cardImage || '/assets/images/3x/gift-card-matcha.jpg',
                pendingVerify: card.status === 'ACTIVE'
              });
            });
            const pendingCount = decorated.filter(card => card.pendingVerify).length;
            const stats = this.data.stats.map(item => {
              if (item.id === 'gift') return Object.assign({}, item, { value: pendingCount });
              return item;
            });
            this.setData({ giftCards: decorated.slice(0, 2), stats });
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

    // 头像加载失败（URL 失效 / 历史脏数据）：回落到默认头像，避免空白
    handleAvatarError() {
      if (this.data.userProfile && this.data.userProfile.avatar === DEFAULT_AVATAR) return;
      const next = Object.assign({}, this.data.userProfile, { avatar: DEFAULT_AVATAR });
      this.setData({ userProfile: next });
    },

    /** 打开授权弹层（供 login-guard 调用） */


    /** 绑定手机号入口 */
    handleBindPhone() {
      loginGuard.requirePhone(null, { reason: '绑定手机号后可下单与领取优惠券' });
    },
    /** 静默登录失败后，用户点击提示条重试 */
    handleRetryLogin() {
      const app = getApp();
      wx.showLoading({ title: '登录中', mask: true });
      loginGuard
        .ensureSilentLogin()
        .then(() => refreshUserProfileFromRemote())
        .then(() => {
          wx.hideLoading();
          if (app && app.globalData) {
            app.globalData.loginFailed = false;
            app.globalData.loginError = '';
            if (typeof app.syncAuthState === 'function') app.syncAuthState();
          }
          wx.showToast({ title: '登录成功', icon: 'success' });
          this.onShow();
        })
        .catch(error => {
          wx.hideLoading();
          if (app && app.globalData) {
            app.globalData.loginFailed = true;
            app.globalData.loginError = (error && error.message) || '登录失败';
          }
          wx.showToast({ title: '登录失败，请稍后重试', icon: 'none' });
        });
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
      loginGuard.requireLogin(
        () => {
          wx.navigateTo({ url: '/pages/gift-card-orders/gift-card-orders' });
        },
        { reason: '登录后可查看礼品卡订单' }
      );
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




