const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { pointsCategories, pointsProducts, pointsSignIn } = require('../../data/mock');
const { getPoints } = require('../../utils/points');
const { resolveStoreCatalog } = require('../../utils/store');

Page(
  withShare({
    data: {
      pointsBalance: 0,
      signedToday: false,
      pointsCategories,
      pointsProducts,
      filteredProducts: pointsProducts,
      activeCategory: 'all',
      currentStore: {}
    },
    onLoad() {
      // 积分商品从后端拉取
      api
        .fetchPointsProducts()
        .then(list => {
          if (Array.isArray(list) && list.length) this.setData({ pointsProducts: list });
        })
        .catch(() => null);      this.syncStore();
    },
    onShow() {
      const app = getApp();
      this.syncStore();
      this.setData({
        pointsBalance: getPoints(),
        signedToday: app.globalData.signedDates.includes(pointsSignIn.today)
      });
    },
    syncStore() {
      const catalog = resolveStoreCatalog();
      this.setData({ currentStore: catalog.currentStore || catalog.stores[0] || {} });
    },
    filterCategory(event) {
      const { id } = event.currentTarget.dataset;
      const filteredProducts = id === 'all' ? pointsProducts : pointsProducts.filter(item => item.category === id);
      this.setData({ activeCategory: id, filteredProducts });
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

