const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const location = require('../../utils/location');
const {
  getFavoriteStoreIds,
  refreshCitiesFromRemote,
  refreshStoreCatalogFromRemote,
  resolveStoreCatalog,
  selectStore: persistSelectedStore,
  decorateStoresWithRealDistance
} = require('../../utils/store');
const { buildStoreMarkers } = require('../../utils/store-markers');
const { refreshMemberLevelsFromRemote } = require('../../utils/member-level');
const {
  RECOMMENDATION_RADIUS_KM,
  annotateAndSortStores,
  buildRecommendationCartItems,
  buildSelectedRecommendationCartItems,
  normalizeRequestItems,
  probeStoreAvailability
} = require('../../utils/recommendation-cart');

function filterStores(stores, keyword) {
  const normalizedKeyword = String(keyword || '')
    .trim()
    .toLowerCase();
  if (!normalizedKeyword) return stores;
  return stores.filter(store => `${store.name}${store.address}`.toLowerCase().indexOf(normalizedKeyword) !== -1);
}

function applyFavoriteState(stores, favoriteStoreIds) {
  return (stores || []).map(store =>
    Object.assign({}, store, {
      isFavorite: favoriteStoreIds.indexOf(store.id) !== -1
    })
  );
}

/**
 * 一键加购独立门店页。
 *
 * 数据流：报告页写 pendingRecommendationAdd -> 本页定位/探测/排序 -> 选店写
 * pendingRecommendationCart -> switchTab 点单页合并购物车。
 * 本页不使用全局菜单镜像探测，避免切店时串数据。
 */
Page(
  withShare({
    data: {
      loading: true,
      error: '',
      cityName: '',
      cityCode: '',
      anchor: { latitude: 28.2282, longitude: 112.9388 },
      markers: [],
      priorityStores: [],
      otherStores: [],
      searchKeyword: '',
      selectingStoreId: '',
      scrollIntoView: '',
      hint: '',
      recommendationCount: 0
    },

    onLoad() {
      this.availability = new Map();
      this.decoratedStores = [];
      this.sortedStores = [];
      this.hasLoadedOnce = false;

      const app = getApp();
      const pending = app.globalData.pendingRecommendationAdd;
      app.globalData.pendingRecommendationAdd = null;
      const items = normalizeRequestItems(pending && pending.items);
      if (!items.length) {
        this.setData({
          loading: false,
          error: '推荐商品已失效，请返回重新发起一键加购'
        });
        return;
      }
      this.pendingIntent = (pending && pending.intent) || 'addAll';
      this.pendingCartScope = (pending && pending.cartScope) || '';
      this.recommendationRequest = {
        reportId: (pending && pending.reportId) || '',
        items
      };
      this.setData({
        recommendationCount: items.length,
        hint: '正在匹配附近门店...'
      });
      this.loadStores();
    },

    onShow() {
      if (!this.recommendationRequest) return;
      if (!this.hasLoadedOnce) {
        this.hasLoadedOnce = true;
        return;
      }
      const catalog = resolveStoreCatalog();
      const nextCityCode = catalog.city ? catalog.city.code : '';
      if (nextCityCode && nextCityCode !== this.data.cityCode) {
        this.loadStores();
      }
    },

    /** 拉城市/门店 -> 定位 -> 真实距离 -> 5公里内可售探测 -> 标注排序。 */
    loadStores() {
      this.setData({ loading: true, error: '' });
      return Promise.all([refreshCitiesFromRemote(), refreshStoreCatalogFromRemote(), refreshMemberLevelsFromRemote()])
        .then(() => location.locate({ force: false }))
        .then(result => {
          const deviceLocated =
            result &&
            (result.source === location.LOCATION_SOURCE.DEVICE || result.source === location.LOCATION_SOURCE.CACHE);
          if (!deviceLocated) {
            wx.showToast({ title: '未开启定位，已按城市中心排序', icon: 'none' });
          }
          const catalog = resolveStoreCatalog();
          this.catalog = catalog;
          const stores = applyFavoriteState(catalog.stores || [], getFavoriteStoreIds());
          const origin = catalog.origin || catalog.city || { latitude: 0, longitude: 0 };
          const anchor = catalog.currentStore
            ? { latitude: catalog.currentStore.latitude, longitude: catalog.currentStore.longitude }
            : { latitude: origin.latitude, longitude: origin.longitude };
          this.setData({
            cityName: catalog.city ? catalog.city.name : '',
            cityCode: catalog.city ? catalog.city.code : '',
            anchor
          });
          return this.decorateStores(stores, origin).then(decorated => {
            this.decoratedStores = decorated;
            return this.probeStores(decorated).then(availability => {
              this.availability = availability;
              this.renderStores();
              this.setData({ loading: false });
            });
          });
        })
        .catch(() => {
          this.setData({ loading: false, error: '附近门店加载失败，请重试' });
        });
    },

    /** 服务端驾车距离优先，失败回落直线距离。 */
    decorateStores(stores, origin) {
      if (!stores.length) return Promise.resolve([]);
      if (!origin || !origin.latitude || !origin.longitude) {
        return Promise.resolve(decorateStoresWithRealDistance(stores, origin));
      }
      return api
        .fetchStoreDistances(origin.latitude, origin.longitude, stores)
        .then(distances => decorateStoresWithRealDistance(stores, origin, distances))
        .catch(() => decorateStoresWithRealDistance(stores, origin));
    },

    /** 只探测 5 公里内门店；探测失败记 unknown，不误判为 0 可售。 */
    probeStores(decorated) {
      const productIds = this.recommendationRequest.items.map(item => item.productId);
      const nearby = (decorated || []).filter(store => Number(store.distanceValue) <= RECOMMENDATION_RADIUS_KM);
      if (!nearby.length) return Promise.resolve(new Map());
      wx.showLoading({ title: '匹配附近门店', mask: true });
      return probeStoreAvailability(nearby, productIds, {
        fetchMenu: api.fetchMenu,
        cache: this.availability
      }).then(
        availability => {
          wx.hideLoading();
          return availability;
        },
        () => {
          wx.hideLoading();
          return new Map();
        }
      );
    },

    /** 标注排序并按搜索词分组渲染。 */
    renderStores() {
      const sorted = annotateAndSortStores(this.decoratedStores || [], this.availability, RECOMMENDATION_RADIUS_KM);
      this.sortedStores = sorted;
      const filtered = filterStores(sorted, this.data.searchKeyword);
      const currentStoreId = this.catalog && this.catalog.currentStore ? this.catalog.currentStore.id : null;
      this.setData({
        priorityStores: filtered.filter(store => store.recommendationPriority),
        otherStores: filtered.filter(store => !store.recommendationPriority),
        markers: buildStoreMarkers(sorted, currentStoreId),
        hint: `已优先展示 ${RECOMMENDATION_RADIUS_KM} 公里内推荐商品最多的门店 · 推荐 ${this.recommendationRequest.items.length} 件`
      });
    },

    handleSearchInput(event) {
      this.setData({ searchKeyword: (event.detail && event.detail.value) || '' });
      this.renderStores();
    },

    clearSearch() {
      this.setData({ searchKeyword: '' });
      this.renderStores();
    },

    selectCity() {
      const catalog = resolveStoreCatalog();
      const cityCode = catalog.city ? catalog.city.code : '';
      wx.navigateTo({ url: `/pages/city-picker/city-picker?city=${cityCode}` });
    },

    handleLocate() {
      if (this.data.loading) return;
      location
        .locate({ force: true })
        .then(result => {
          const deviceLocated =
            result &&
            (result.source === location.LOCATION_SOURCE.DEVICE || result.source === location.LOCATION_SOURCE.CACHE);
          wx.showToast({
            title: deviceLocated ? `已定位到${result.cityName || '当前位置'}` : '未开启定位，已按城市中心排序',
            icon: 'none'
          });
          return this.loadStores();
        })
        .catch(() => {
          wx.showToast({ title: '定位失败，请稍后重试', icon: 'none' });
        });
    },

    handleMarkerTap(event) {
      const markerId = event && event.detail && event.detail.markerId;
      const marker = (this.data.markers || []).find(item => item.id === markerId);
      if (!marker) return;
      const store = this.sortedStores.find(item => item.id === marker.storeId);
      if (!store) return;
      this.setData({
        anchor: { latitude: store.latitude, longitude: store.longitude },
        scrollIntoView: 'store-' + store.id
      });
    },

    handleBack() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.switchTab({ url: '/pages/tongue/tongue' });
    },

    handleStorePhone(event) {
      const store = event.detail.store;
      if (!store || !store.phone) return;
      wx.makePhoneCall({ phoneNumber: store.phone });
    },

    handleStoreNavigate(event) {
      const store = event.detail.store;
      if (!store || !store.latitude || !store.longitude) return;
      wx.openLocation({
        latitude: store.latitude,
        longitude: store.longitude,
        name: store.name,
        address: store.address,
        scale: 16
      });
    },

    /** 取门店菜单：优先用探测缓存，未探测或失败时现场请求。 */
    loadStoreMenu(store) {
      const cached = this.availability.get(store.id);
      if (cached && cached.status !== 'unknown' && Array.isArray(cached.menuTabs)) {
        return Promise.resolve(cached.menuTabs);
      }
      if (store.subjectId == null) {
        return Promise.reject(new Error('门店主体编号缺失'));
      }
      return api.fetchMenu(store.subjectId).then(list => (Array.isArray(list) ? list : []));
    },

    finishCheckoutSelection(store, built) {
      const app = getApp();
      app.globalData.pendingOrder = {
        reportId: this.recommendationRequest.reportId,
        items: built.items,
        storeId: store.id,
        orderMode: 'pickup',
        fromCart: this.pendingIntent === 'cartCheckout',
        cartScope: this.pendingIntent === 'cartCheckout' ? this.pendingCartScope : undefined
      };
      wx.redirectTo({ url: '/pages/order-confirm/order-confirm' });
    },

    handleSelectStore(event) {
      const store = event.detail && event.detail.store;
      if (!store || !store.id || this.data.selectingStoreId) return;
      const isAddAll = this.pendingIntent !== 'buyNow' && this.pendingIntent !== 'cartCheckout';
      this.setData({ selectingStoreId: store.id });
      wx.showLoading({ title: isAddAll ? '正在加购' : '正在校验', mask: true });
      this.loadStoreMenu(store)
        .then(menuTabs => {
          const built = isAddAll
            ? buildRecommendationCartItems(menuTabs, this.recommendationRequest.items)
            : buildSelectedRecommendationCartItems(menuTabs, this.recommendationRequest.items);
          if (!built.items.length) {
            wx.hideLoading();
            this.setData({ selectingStoreId: '' });
            wx.showToast({ title: '该门店所选商品均已下架，请选择其他门店', icon: 'none' });
            return;
          }
          persistSelectedStore(store.id);
          wx.hideLoading();
          this.setData({ selectingStoreId: '' });

          if (isAddAll) {
            getApp().globalData.pendingRecommendationCart = {
              reportId: this.recommendationRequest.reportId,
              storeId: store.id,
              items: built.items,
              missing: built.missing
            };
            wx.switchTab({ url: '/pages/menu/menu' });
            return;
          }

          if (!built.missing.length) {
            this.finishCheckoutSelection(store, built);
            return;
          }

          const names = built.missing.map(item => item.name).filter(Boolean);
          const missingText = names.length ? names.join('、') : `${built.missing.length}件商品`;
          wx.showModal({
            title: '部分商品不可售',
            content: `${missingText}在当前门店不可售，其余商品将进入结算`,
            confirmText: '继续结算',
            cancelText: '重新选店',
            success: result => {
              if (result.confirm) this.finishCheckoutSelection(store, built);
            }
          });
        })
        .catch(() => {
          wx.hideLoading();
          this.setData({ selectingStoreId: '' });
          wx.showToast({ title: '门店商品加载失败，请重试', icon: 'none' });
        });
    }
  })
);
