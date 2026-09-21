const { pointsRecords, exchangeRecords, userProfile } = require('./data/mock');
const { handleAppHide, resolveLocationContext, resolveStoreCatalog, resolveStorePreference } = require('./utils/store');
const loginGuard = require('./utils/login-guard');

function syncStoreGlobals(app, catalog) {
  app.globalData.selectedStoreId = catalog.currentStore ? catalog.currentStore.id : null;
  app.globalData.selectedCityCode = catalog.city.code;
  app.globalData.selectedCityName = catalog.city.name;
}

App({
  globalData: {
    selectedStoreId: null,
    selectedCityCode: 'changsha',
    selectedCityName: '长沙市',
    orderMode: 'pickup',
    menuTabId: 'classic',
    points: userProfile.points,
    signedDates: [],
    continuousDays: 0,
    pointsRecords: pointsRecords.map(item => Object.assign({}, item)),
    exchangeRecords: exchangeRecords.map(item => Object.assign({}, item)),
    exchangeVerifyPool: [],
    loggedIn: false,
    user: null
  },
  onLaunch() {
    resolveStorePreference();
    resolveLocationContext();
    syncStoreGlobals(this, resolveStoreCatalog());

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

