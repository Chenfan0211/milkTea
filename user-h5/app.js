const {
  refreshStoreCatalogFromRemote,
  refreshCitiesFromRemote,
  handleAppHide,
  resolveLocationContext,
  resolveStoreCatalog,
  resolveStorePreference,
  selectStore
} = require('./utils/store');
const entryLogin = require('./utils/entry-login');
const authState = require('./utils/auth-state');
const { refreshUserProfileFromRemote } = require('./utils/user-profile');

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
    // 登录态唯一来源（anonymous / authorized / full），不再使用无维护的 loggedIn 布尔值
    authState: { level: 'anonymous', label: '未登录', hasToken: false, hasPhone: false, phone: '' },
    user: null,
    // 静默登录失败标记与原因：页面据此决定是否提示「登录失败，请重试」
    loginFailed: false,
    loginError: ''
  },
  /** 把最新登录态同步到 globalData，供页面无侵入读取 */
  syncAuthState() {
    this.globalData.authState = authState.getAuthState();
    return this.globalData.authState;
  },
  onLaunch() {
    // 门店 / 城市数据来自后端接口（阶段 C 后不再有本地假数据）。
    Promise.all([refreshCitiesFromRemote(), refreshStoreCatalogFromRemote()]).then(() => {
      syncStoreGlobals(this, resolveStoreCatalog());
    });

    // 入口静默登录：wx.login 换 token，用户无感；带超时兜底，失败不阻塞启动。
    // ensureEntryLogin 内部已串好「静默登录 -> 拉取用户资料」，且永不 reject，
    // 因此这里只在 then 里同步状态，不需要 catch。
    // 失败时记录 loginFailed 供页面感知（避免「静默 401 一片红」而无任何提示），
    // 真正需要登录的操作仍由 loginGuard 引导授权。
    entryLogin.ensureEntryLogin().then(result => {
      this.globalData.loginFailed = !result.ok;
      this.globalData.loginError = result.ok ? '' : (result.error && result.error.message) || '登录失败';
      // 资料拉取完成后再同步：此时 phone 才可能就绪，状态更准确
      this.syncAuthState();
      if (!result.ok && !result.timedOut) {
        // 开发期可见的排查线索：40029 通常意味着工具登录的微信号不是该小程序成员
        console.warn('[login] 静默登录失败：', this.globalData.loginError);
      }
    });
  },
  onShow() {
    syncStoreGlobals(this, resolveStoreCatalog());
    this.syncAuthState();
  },
  onHide() {
    handleAppHide();
  }
});


