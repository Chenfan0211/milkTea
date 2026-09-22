const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getFavoriteStoreIds, resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');

function resolveStores(couponId, activeStores, couponList) {
  const coupon = (couponList || []).find(item => item.id === couponId);
  const applicableStoreIds = coupon && Array.isArray(coupon.applicableStoreIds) ? coupon.applicableStoreIds : [];

  if (!applicableStoreIds.length) return activeStores.map(store => Object.assign({}, store));

  const applicableStores = activeStores.filter(store => applicableStoreIds.indexOf(store.id) !== -1);
  return (applicableStores.length ? applicableStores : activeStores).map(store => Object.assign({}, store));
}

Page(
  withShare({
    data: {
      couponId: '',
      nextPage: '',
      from: '',
      title: '选择商品适用门店',
      currentCity: '长沙市',
      currentAddress: '',
      selectedStoreId: '',
      stores: []
    },
    onLoad(options) {
      // 标题只依赖路由参数，先同步设置，避免等待接口期间标题为默认值
      this.setData({
        title: options.from === 'points' || options.from === 'stored-value' ? '选择门店' : '选择商品适用门店'
      });
      // 优惠券数据从后端拉取，再据此计算适用门店
      const catalog = resolveStoreCatalog();
      const activeStores = catalog.stores;
      const favoriteStoreIds = getFavoriteStoreIds();
      api
        .fetchCoupons()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(couponList => {
          const applicableStores = resolveStores(options.couponId, activeStores, couponList).map(store =>
        Object.assign({}, store, {
          isFavorite: favoriteStoreIds.indexOf(store.id) !== -1
        })
      );
          const firstStore = applicableStores[0] || activeStores[0];
          this.setData({
            couponId: options.couponId || '',
            from: options.from || '',
            nextPage: options.next === 'products' ? 'products' : '',
            currentCity: catalog.city ? catalog.city.name : '',
            stores: applicableStores,
            selectedStoreId: catalog.currentStore ? catalog.currentStore.id : '',
            currentAddress: firstStore ? firstStore.address : ''
          });
        });
    },
    showUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    },
    handleSelectStore(event) {
      const store = event.detail && event.detail.store;
      if (!store) return;
      const { id } = store;
      if (!id) return;
      if (this.data.nextPage === 'products') {
        wx.navigateTo({ url: `/pages/coupon-products/coupon-products?couponId=${this.data.couponId}&storeId=${id}` });
        return;
      }
      persistSelectedStore(id);
      getApp().globalData.selectedStoreId = id;
      wx.navigateBack();
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

