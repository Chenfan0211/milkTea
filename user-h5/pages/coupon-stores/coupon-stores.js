const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getFavoriteStoreIds, resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');

function resolveStores(couponId, activeStores, couponList) {
  // couponId 传的是券模板数字 id；统一按字符串比较，兼容路由参数为字符串。
  const coupon = (couponList || []).find(item => String(item.id) === String(couponId));
  const applicableStoreIds = coupon && Array.isArray(coupon.applicableStoreIds) ? coupon.applicableStoreIds : [];

  if (!applicableStoreIds.length) return activeStores.map(store => Object.assign({}, store));

  // 后端 applicableStoreIds 是数字 subjectId，前端门店 id 是业务 code，
  // 必须按 store.subjectId 过滤，否则限定门店的券会错误地展示全部门店。
  const applicableStores = activeStores.filter(store =>
    applicableStoreIds.some(id => String(id) === String(store.subjectId))
  );
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
      currentCityCode: '',
      currentAddress: '',
      selectedStoreId: '',
      stores: []
    },
    onLoad(options) {
      // 标题只依赖路由参数，先同步设置，避免等待接口期间标题为默认值
      this.setData({
        title: ['points', 'stored-value', 'gift-card'].includes(options.from) ? '选择门店' : '选择商品适用门店',
        couponId: options.couponId || '',
        from: options.from || '',
        nextPage: options.next === 'products' ? 'products' : ''
      });
      // 优惠券数据从后端拉取，再据此计算适用门店。
      // 券列表缓存到实例上：切换城市（onShow）时复用同一份，避免重复请求。
      api
        .fetchCoupons()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(couponList => {
          this.couponList = couponList;
          this.syncStoresByCity();
        });
    },
    /**
     * 按当前城市重算城市名与门店列表（onLoad 拉券后、onShow 从城市页返回后共用）。
     * 城市与门店均以 utils/store 的持久化选店偏好为唯一数据源。
     */
    syncStoresByCity() {
      const catalog = resolveStoreCatalog();
      const activeStores = catalog.stores;
      const favoriteStoreIds = getFavoriteStoreIds();
      const applicableStores = resolveStores(this.data.couponId, activeStores, this.couponList).map(store =>
        Object.assign({}, store, {
          isFavorite: favoriteStoreIds.indexOf(store.id) !== -1
        })
      );
      const firstStore = applicableStores[0] || activeStores[0];
      // 切换城市会清空已选门店：原门店不在新城市时 selectedStoreId 必须回落为空，
      // 否则列表里会出现「没有任何门店被选中、状态却记着旧门店」的错乱。
      const currentStoreId = catalog.currentStore ? catalog.currentStore.id : '';
      this.setData({
        currentCity: catalog.city ? catalog.city.name : '',
        currentCityCode: catalog.city ? catalog.city.code : '',
        stores: applicableStores,
        selectedStoreId: currentStoreId,
        currentAddress: firstStore ? firstStore.address : ''
      });
    },
    /**
     * 从「选择城市」页返回时刷新城市与门店列表（与点单页共用 city-picker）。
     * 切城市后留在本页刷新，不自动返回上一页。
     */
    onShow() {
      // 首次 onShow 紧跟 onLoad，此时券列表尚未回来，交由 onLoad 的异步链处理，避免用空券列表覆盖。
      if (typeof this.couponList === 'undefined') return;
      const catalog = resolveStoreCatalog();
      const nextCityCode = catalog.city ? catalog.city.code : '';
      // 城市未变化时不重复 setData，避免返回时无谓重渲染
      if (nextCityCode === this.data.currentCityCode) return;
      this.syncStoresByCity();
    },
    /** 打开通用「选择城市」页（与点单页 selectCity 行为一致），返回后由 onShow 刷新。 */
    selectCity() {
      const catalog = resolveStoreCatalog();
      const cityCode = catalog.city ? catalog.city.code : '';
      wx.navigateTo({ url: `/pages/city-picker/city-picker?city=${cityCode}` });
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
