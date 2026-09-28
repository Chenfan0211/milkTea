const { withShare } = require('../../utils/share');
const { DEFAULT_AVATAR, getUserProfile, maskPhone, roundMoney, saveUserProfile, refreshUserProfileFromRemote } = require('../../utils/user-profile');
const { getPoints, notifyPointsChanged } = require('../../utils/points');
const { buildLevelMeta, refreshMemberLevelsFromRemote } = require('../../utils/member-level');
const { getCurrentBusinessRole, getPendingRoles, getDashboard } = require('../../utils/roles');
const loginGuard = require('../../utils/login-guard');
const auth = require('../../utils/auth');
const authState = require('../../utils/auth-state');
const api = require('../../utils/api');
const { refreshCouponsFromRemote } = require('../../utils/coupons');
const { pickGiftCardRecords, resolveGiftCardDisplay } = require('../../utils/gift-card');

const initialProfile = getUserProfile();
const initialMeta = buildLevelMeta(initialProfile);

/**
 * 金额展示：储值余额固定 2 位小数（940.2 -> "940.20"）。
 * value 保持 number 类型（便于断言与计算），display 专供渲染。
 */
function formatBalance(value) {
  return roundMoney(Number(value) || 0).toFixed(2);
}

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
        { id: 'balance', label: '储值余额', value: initialProfile.balance, display: formatBalance(initialProfile.balance) },
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
      defaultAvatar: DEFAULT_AVATAR,
      // 登录弹层：未登录时「我的」页的登录入口（页内唤起，不跳独立页）
      loginSheetVisible: false
    },
    onLoad() {
      this.syncLevel();
      // 会员等级来自接口：拉取后重建等级卡（否则等级名与进度条为空）
      refreshMemberLevelsFromRemote().then(() => this.syncLevel());
      // 订阅时光币广播：其它页签到 / 兑换 / 远端刷新后，本页卡片立即同步
      const app = getApp();
      if (app && typeof app.subscribePoints === 'function') {
        this.unsubscribePoints = app.subscribePoints(points => {
          const app2 = getApp();
          if (app2 && app2.globalData) app2.globalData.points = points;
          const stats = this.data.stats.map(item =>
            item.id === 'points' ? Object.assign({}, item, { value: points }) : item
          );
          this.setData({ stats });
        });
      }
    },
    onUnload() {
      if (typeof this.unsubscribePoints === 'function') this.unsubscribePoints();
    },
    /**
     * 重算资产统计卡（时光币 / 储值余额 / 优惠券 / 礼品卡）。
     *
     * 为什么抽成方法：onShow 先渲染本地缓存，远端 fetchMe 返回后还要再刷一次。
     * 原先这两处各写一份 stats 组装逻辑，远端回来时漏了刷新，
     * 导致「我的」页时光币长期停留在旧值（与时光币商城不一致）。
     *
     * 同时把最新时光币写回 globalData 会话镜像，保证其他页面读到同一份数据。
     */
    refreshAssetStats() {
      const userProfile = getUserProfile();
      const points = Number(userProfile.points) || 0;
      const app = getApp();
      if (app && app.globalData) app.globalData.points = points;
      const stats = this.data.stats.map(item => {
        if (item.id === 'points') return Object.assign({}, item, { value: points });
        if (item.id === 'balance')
          return Object.assign({}, item, { value: userProfile.balance, display: formatBalance(userProfile.balance) });
        if (item.id === 'coupon') return Object.assign({}, item, { value: userProfile.couponCount });
        if (item.id === 'gift')
          return Object.assign({}, item, { value: userProfile.giftCards.length });
        return item;
      });
      this.setData({
        stats,
        userProfile,
        giftCards: userProfile.giftCards.slice(0, 2)
      });
    },

    onShow() {
      const app = getApp();
      const userProfile = getUserProfile();
      const businessRole = getCurrentBusinessRole();
      const pendingRoles = getPendingRoles();
      // 先用本地缓存渲染（秒出），随后远端返回时会再次刷新资产卡
      this.refreshAssetStats();
      this.setData({
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
          .fetchMe(true)
          .then(remote => {
            this.setData({ remoteUser: remote, phoneMasked: remote.phone ? maskPhone(remote.phone) : '' });
            // 回写本地资料，供其他页面复用。
            //
            // ⚠️ 必须是「合并更新」而不是重建：saveUserProfile 内部是
            // Object.assign({}, EMPTY_PROFILE, source)，未传的字段会回落到
            // 空壳的 0 / []。若这里只挑 nickname/avatar 等字段传进去，
            // points / balance / couponCount / giftCards 会被整体清零 ——
            // 表现为「从其它页返回我的页，时光币/余额/优惠券全部变 0」。
            // 因此先取当前缓存，再只覆盖远端明确返回的字段。
            saveUserProfile(
              Object.assign({}, userProfile, {
                nickname: remote.nickName || userProfile.nickname,
                // 未授权头像时回落到默认头像，避免头像区空白
                avatar: remote.avatar || userProfile.avatar || DEFAULT_AVATAR,
                phone: remote.phone || userProfile.phone,
                // 资产字段以远端为准（远端缺失时保留本地值，不覆盖成 0）
                points: Number.isFinite(Number(remote.points)) ? Number(remote.points) : userProfile.points,
                balance: Number.isFinite(Number(remote.balance))
                  ? roundMoney((Number(remote.balance) || 0) / 100)
                  : userProfile.balance,
                // 成长值/累计消费：后端 totalSpend 为「分」，前端按「元」展示
                totalSpend: Number.isFinite(Number(remote.totalSpend))
                  ? roundMoney((Number(remote.totalSpend) || 0) / 100)
                  : userProfile.totalSpend,
                growth: Number.isFinite(Number(remote.totalSpend))
                  ? roundMoney((Number(remote.totalSpend) || 0) / 100)
                  : userProfile.growth,
                vipLevel: remote.vipLevel || userProfile.vipLevel,
                region: userProfile.region
              })
            );
            // 关键：远端返回后必须重算资产卡（含时光币），
            // 否则卡片停留在 onShow 那一刻的本地旧值 ——
            // 表现为「商城显示 25、我的页仍显示 13」。
            this.refreshAssetStats();
            // 广播给其它页面（商城 / 明细等），保证全端余额一致
            notifyPointsChanged(getPoints(), { source: 'profile-refresh' });
          })
          .catch(() => {});
        // 我的优惠券数量与优惠券列表共用同一远端刷新方法，统一只取 UNUSED 可用券。
        refreshCouponsFromRemote('UNUSED')
          .then(coupons => {
            const count = Array.isArray(coupons) ? coupons.length : 0;
            const stats = this.data.stats.map(item =>
              item.id === 'coupon' ? Object.assign({}, item, { value: count }) : item
            );
            this.setData({ stats });
          })
          .catch(() => {
            // 保留上一次成功结果，失败时不清零，避免把网络异常误显示为零张。
          });
        // 我的礼品卡：接口直接返回历史展示元数据，不再依赖仅上架面额接口。
        api
          .fetchMyGiftCards()
          .then(cards => {
            const cardList = pickGiftCardRecords(cards);
            const decorated = cardList.map(card => {
              const display = resolveGiftCardDisplay(card);
              return Object.assign({}, card, {
                name: display.name,
                image: display.image,
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


    /**
     * 用户卡片点击分流：
     *   · 未登录 → 弹登录层（个人资料需登录态才能取数，直接跳会空白）
     *   · 已登录 → 跳个人资料页
     */
    handleUserCardTap() {
      if (this.data.authStateLevel === 'anonymous') {
        this.openLogin();
        return;
      }
      this.openProfileData();
    },
    /** 未登录时的登录入口：页内唤起登录弹层（不跳独立授权页） */
    openLogin() {
      this.setData({ loginSheetVisible: true });
    },
    closeLoginSheet() {
      this.setData({ loginSheetVisible: false });
    },
    /** 弹层内「暂时跳过」：仅关闭弹层，页面保持未登录态（个人数据仍隐藏） */
    handleLoginSkip() {
      this.setData({ loginSheetVisible: false });
      loginGuard.clearPendingAction();
    },
    /** 弹层内授权成功：刷新登录态与资料，并续跑被拦截的操作 */
    handleLoginSuccess() {
      this.setData({ loginSheetVisible: false });
      refreshUserProfileFromRemote()
        .then(() => {
          if (typeof this.syncUserCard === 'function') this.syncUserCard();
          this.onShow();
        })
        .catch(() => null);
      loginGuard.flushPendingAction();
    },
    handleLoginFail() {
      loginGuard.toast('登录失败，请稍后重试');
    },

    /** 绑定手机号入口 */
    handleBindPhone() {
      loginGuard.requirePhone(null, { reason: '绑定手机号后可下单与领取优惠券' });
    },

    /** 点击卡片手机号：走手机号授权（更换/绑定），复用 handleBindPhone 的 requirePhone 链路 */
    handlePhoneTap() {
      this.handleBindPhone();
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
    openGiftCardOrders() {
      loginGuard.requireLogin(
        () => {
          wx.navigateTo({ url: '/pages/gift-card-orders/gift-card-orders' });
        },
        { reason: '登录后可查看礼品卡订单' }
      );
    },
    /**
     * 打开「我的礼品卡」。
     *
     * 必须带 ?tab=mine：gift-card 页的默认 tab 是「buy（购买）」，
     * 不带参数会落到购买页，用户会以为「点进去没有我的卡」。
     * 该页 onLoad 已支持 tab=mine 直接切到「我的」。
     */
    openGiftCards() {
      loginGuard.requireLogin(
        () => {
          wx.navigateTo({ url: '/pages/gift-card/gift-card?tab=mine' });
        },
        { reason: '登录后可查看礼品卡' }
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
      wx.navigateTo({ url: '/packageRole/role-center/role-center' });
    },
    openRoleApply() {
      wx.navigateTo({ url: '/packageRole/role-apply/role-apply' });
    },
    openRoleFunction(event) {
      const { action } = event.currentTarget.dataset;
      const roleId = this.data.businessRole && this.data.businessRole.id;
      if (!roleId || !action) return;
      const routeMap = {
        verify: '/packageRole/role-verify/role-verify',
        products: '/packageRole/role-products/role-products',
        invest: '/packageRole/role-invest/role-invest',
        orders: '/packageRole/resource-orders/resource-orders',
        income: '/packageRole/role-income/role-income',
        withdraw: '/packageRole/role-withdraw/role-withdraw'
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
        this.openGiftCardOrders();
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
      // 收货地址：小程序未单独建页，收货信息在下单流程内填写，这里引导去点单
      if (id === 'address') {
        wx.showToast({ title: '收货信息可在下单时填写', icon: 'none' });
        return;
      }
      // 活动报名 / 活动规则：跳活动规则页
      if (id === 'activity') {
        wx.navigateTo({ url: '/pages/activity-rules/activity-rules' });
        return;
      }
      // 兜底：配置了但前端未接入的入口，明确提示而不是静默无反应
      wx.showToast({ title: `${label || '功能'}暂未接入`, icon: 'none' });
    },
    showUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);




