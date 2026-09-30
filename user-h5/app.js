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
    // 时光币变更订阅者（页面 onLoad 注册、onUnload 注销）
    pointsListeners: [],
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
  /**
   * 订阅时光币变更。页面 onLoad 调用，返回取消订阅函数（onUnload 时执行）。
   *
   * 用于解决「签到 / 兑换后，其它页面仍显示旧时光币」的问题：
   * 所有余额变更统一经 utils/points.notifyPointsChanged() 广播，
   * 页面按需刷新，而不是各自读各自缓存的快照。
   *
   * @param {(points:number, source:string)=>void} listener
   * @returns {()=>void} 取消订阅
   */
  subscribePoints(listener) {
    if (typeof listener !== 'function') return () => {};
    if (!Array.isArray(this.globalData.pointsListeners)) this.globalData.pointsListeners = [];
    this.globalData.pointsListeners.push(listener);
    return () => {
      this.globalData.pointsListeners = (this.globalData.pointsListeners || []).filter(item => item !== listener);
    };
  },

  /**
   * 广播时光币变更（由 utils/points.notifyPointsChanged 调用）。
   *
   * 订阅者异常不得中断其它订阅者，逐个 try/catch 隔离。
   */
  publishPointsChanged(points, source) {
    this.globalData.points = points;
    const listeners = Array.isArray(this.globalData.pointsListeners) ? this.globalData.pointsListeners.slice() : [];
    listeners.forEach(listener => {
      try {
        listener(points, source || 'unknown');
      } catch (error) {
        // 单个页面回调异常不影响其它页面刷新
      }
    });
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
