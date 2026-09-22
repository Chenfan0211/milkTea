const {
  refreshStoreCatalogFromRemote,
  refreshCitiesFromRemote,
  handleAppHide,
  resolveLocationContext,
  resolveStoreCatalog,
  resolveStorePreference,
  selectStore
} = require('./utils/store');
const loginGuard = require('./utils/login-guard');

function syncStoreGlobals(app, catalog) {
  app.globalData.selectedStoreId = catalog.currentStore ? catalog.currentStore.id : null;
  app.globalData.selectedCityCode = catalog.city ? catalog.city.code : '';
  app.globalData.selectedCityName = catalog.city ? catalog.city.name : '';
}

App({
  globalData: {
    selectedStoreId: null,
    selectedCityCode: '',
    selectedCityName: '',
    orderMode: 'pickup',
    menuTabId: 'classic',
    points: 0,
    signedDates: [],
    continuousDays: 0,
    pointsRecords: [],
    exchangeRecords: [],
    exchangeVerifyPool: [],
    loggedIn: false,
    user: null
  },
  onLaunch() {
    // 门店 / 城市数据来自后端接口（阶段 C 后不再有本地假数据）。
    Promise.all([refreshCitiesFromRemote(), refreshStoreCatalogFromRemote()]).then(() => {
      syncStoreGlobals(this, resolveStoreCatalog());
    });

    // 静默登录：wx.login 换 token，用户无感；失败不阻塞启动，
    // 仅在需要登录的操作（下单/领券等）时再引导授权。
    loginGuard.ensureSilentLogin().catch(() => {});
  },
  onShow() {
    syncStoreGlobals(this, resolveStoreCatalog());
  },
  onHide() {
    handleAppHide();
  }
});
