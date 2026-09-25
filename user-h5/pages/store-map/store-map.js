const { withShare } = require('../../utils/share');
const { resolveStoreCatalog } = require('../../utils/store');
const { buildStoreMarkers } = require('../../utils/store-markers');

/**
 * 门店地图页。
 *
 * 为什么单独成页：点单页运行在 Skyline 渲染器下，而 Skyline 对 <map>
 * 这类原生组件支持受限，内嵌地图会白屏。本页显式声明 renderer: webview
 * （见 store-map.json），在不影响点单页 Skyline 策略的前提下正常渲染地图。
 */
Page(
  withShare({
    data: {
      anchor: { latitude: 28.2282, longitude: 112.9388 },
      markers: [],
      stores: [],
      activeStore: {}
    },
    onLoad() {
      this.syncFromCatalog();
    },
    onShow() {
      // 从列表页选择门店后返回时同步最新数据
      this.syncFromCatalog();
    },
    syncFromCatalog() {
      const catalog = resolveStoreCatalog();
      const stores = catalog.stores || [];
      const currentStore = catalog.currentStore || null;
      const city = catalog.city || { latitude: 0, longitude: 0 };
      // 定位到当前门店；未选门店时回落到城市中心
      const anchor = currentStore
        ? { latitude: currentStore.latitude, longitude: currentStore.longitude }
        : { latitude: city.latitude, longitude: city.longitude };
      this.setData({
        stores,
        markers: buildStoreMarkers(stores, currentStore && currentStore.id),
        activeStore: currentStore || {}
      });
      if (anchor.latitude && anchor.longitude) this.setData({ anchor });
    },
    /** 点击地图标记：切换当前门店并展示信息卡 */
    handleMarkerTap(event) {
      const markerId = event && event.detail && event.detail.markerId;
      const marker = this.data.markers.find(item => item.id === markerId);
      if (!marker) return;
      const store = this.data.stores.find(item => item.id === marker.storeId);
      if (!store) return;
      this.setData({
        activeStore: store,
        anchor: { latitude: store.latitude, longitude: store.longitude }
      });
    },
    handleBack() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.switchTab({ url: '/pages/menu/menu' });
    },
    handlePhone() {
      const phone = this.data.activeStore && this.data.activeStore.phone;
      if (!phone) {
        wx.showToast({ title: '门店电话暂未配置', icon: 'none' });
        return;
      }
      wx.makePhoneCall({ phoneNumber: phone });
    },
    handleNavigate() {
      const store = this.data.activeStore;
      if (!store || !store.latitude || !store.longitude) {
        wx.showToast({ title: '门店位置暂未配置', icon: 'none' });
        return;
      }
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
