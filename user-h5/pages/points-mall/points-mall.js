const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getPoints, notifyPointsChanged } = require('../../utils/points');
const { refreshUserProfileFromRemote } = require('../../utils/user-profile');
const { resolveStoreCatalog } = require('../../utils/store');

const ALL_CATEGORY = { id: 'all', label: '全部' };

function normalizeCategories(list) {
  const enabledCategories = (Array.isArray(list) ? list : [])
    .filter(item => item && (item.enabled === undefined || item.enabled === true || Number(item.enabled) === 1))
    .map(item => {
      const id = String(item.code || item.id || '');
      if (!id || id === 'all') return null;
      return { id, label: item.name || item.label || id };
    })
    .filter(Boolean);
  return [ALL_CATEGORY].concat(enabledCategories);
}

function filterProductsByCategory(products, categoryId) {
  const list = Array.isArray(products) ? products : [];
  if (categoryId === 'all') return list;
  return list.filter(item => item && item.category === categoryId);
}

Page(
  withShare({
    data: {
      pointsBalance: 0,
      signedToday: false,
      pointsCategories: [ALL_CATEGORY],
      pointsProducts: [],
      filteredProducts: [],
      activeCategory: 'all',
      currentStore: {}
    },
    onLoad() {
      this.syncStore();
      // 订阅时光币广播：签到 / 兑换 / 远端刷新后，商城余额立即跟随更新
      const app = getApp();
      if (app && typeof app.subscribePoints === 'function') {
        this.unsubscribePoints = app.subscribePoints(points => {
          this.setData({ pointsBalance: points });
        });
      }
      const categoriesRequest = api.fetchPointsCategories().catch(() => []);
      const productsRequest = api.fetchPointsProducts().catch(() => []);
      return Promise.all([categoriesRequest, productsRequest]).then(([categories, products]) => {
        const pointsCategories = normalizeCategories(categories);
        const list = Array.isArray(products) ? products : [];
        const activeCategory = pointsCategories.some(item => item.id === this.data.activeCategory)
          ? this.data.activeCategory
          : 'all';
        this.setData({
          pointsCategories,
          pointsProducts: list,
          filteredProducts: filterProductsByCategory(list, activeCategory),
          activeCategory
        });
      });
    },
    onShow() {
      const app = getApp();
      this.syncStore();
      this.setData({
        pointsBalance: getPoints(),
        signedToday: Boolean(this.data.todayKey) && app.globalData.signedDates.includes(this.data.todayKey)
      });
      // 远端刷新后统一广播，保证「我的」页与商城读到同一份余额
      refreshUserProfileFromRemote()
        .then(profile => notifyPointsChanged(profile.points, { source: 'points-mall' }))
        .catch(() => null);
    },
    onUnload() {
      if (typeof this.unsubscribePoints === 'function') this.unsubscribePoints();
    },
    syncStore() {
      const catalog = resolveStoreCatalog();
      this.setData({ currentStore: catalog.currentStore || catalog.stores[0] || {} });
    },
    filterCategory(event) {
      const id = String((event.currentTarget.dataset && event.currentTarget.dataset.id) || 'all');
      const activeCategory = id === 'all' || this.data.pointsCategories.some(item => item.id === id) ? id : 'all';
      this.setData({
        activeCategory,
        filteredProducts: filterProductsByCategory(this.data.pointsProducts, activeCategory)
      });
    },
    openProduct(event) {
      wx.navigateTo({ url: `/pages/points-exchange/points-exchange?id=${event.currentTarget.dataset.id}` });
    },
    openPointsDetail() {
      wx.navigateTo({ url: '/pages/points-detail/points-detail' });
    },
    openExchangeRecords() {
      wx.navigateTo({ url: '/pages/exchange-records/exchange-records' });
    },
    selectStore() {
      wx.navigateTo({ url: '/pages/coupon-stores/coupon-stores?from=points' });
    },
    handleCheckIn() {
      wx.navigateTo({ url: '/pages/points-signin/points-signin' });
    },
    openSignInRules() {
      wx.navigateTo({ url: '/pages/points-signin-rules/points-signin-rules' });
    },
    showUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);
