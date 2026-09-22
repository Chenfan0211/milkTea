const { withShare } = require('../../utils/share');
const {
  getFavoriteStoreIds,
  resolveStoreCatalog,
  selectStore: persistSelectedStore,
  toggleFavoriteStore
} = require('../../utils/store');

Page(
  withShare({
    data: {
      currentCityName: '',
      currentStoreId: '',
      favoriteStores: []
    },
    onLoad() {
      this.loadFavorites();
    },
    onShow() {
      this.loadFavorites();
    },
    loadFavorites() {
      const catalog = resolveStoreCatalog();
      const favoriteStoreIds = getFavoriteStoreIds();
      // 门店/城市来自接口，未就绪时降级为空，避免页面崩溃
      const favoriteStores = (catalog.stores || [])
        .filter(store => favoriteStoreIds.indexOf(store.id) !== -1)
        .map(store => Object.assign({}, store, { isFavorite: true }));
      this.setData({
        currentCityName: catalog.city ? catalog.city.name : '',
        currentStoreId: catalog.currentStore ? catalog.currentStore.id : '',
        favoriteStores
      });
    },
    handleSelectFavoriteStore(event) {
      const store = event.detail.store;
      if (!store) return;
      persistSelectedStore(store.id);
      const app = getApp();
      app.globalData.selectedStoreId = store.id;
      app.globalData.selectedCityCode = store.cityCode;
      app.globalData.selectedCityName = this.data.currentCityName;
      app.globalData.favoriteStoreSelected = true;
      wx.navigateBack();
    },
    handleRemoveFavorite(event) {
      const { id } = event.detail.store;
      const result = toggleFavoriteStore(id);
      this.loadFavorites();
      wx.showToast({ title: result.favorite ? '已收藏' : '已取消收藏', icon: 'none' });
    },
    handlePhone(event) {
      const store = event.detail.store;
      if (!store || !store.phone) return;
      wx.makePhoneCall({ phoneNumber: store.phone });
    },
    handleNavigate(event) {
      const store = event.detail.store;
      if (!store) return;
      wx.openLocation({
        latitude: store.latitude,
        longitude: store.longitude,
        name: store.name,
        address: store.address,
        scale: 16
      });
    }
  })
);
