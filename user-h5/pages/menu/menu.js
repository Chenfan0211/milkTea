const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const {
  getListedMenuTabs,
  getMergedMenuTab,
  isProductListed,
  refreshMenuFromRemote,
  getMenuCatalog,
  getMenuSyncState
} = require('../../utils/product-listing');
const { buildCartId, mergeEditedCartItem } = require('../../utils/cart');
const { buildStoreMarkers } = require('../../utils/store-markers');
const { normalizeSpecProduct } = require('../../utils/spec-sheet');
const { refreshMemberLevelsFromRemote } = require('../../utils/member-level');
const {
  getFavoriteStoreIds,
  refreshCitiesFromRemote,
  refreshStoreCatalogFromRemote,
  resolveStoreCatalog,
  selectStore: persistSelectedStore,
  toggleFavoriteStore,
  useDeviceLocation,
  decorateStoresWithRealDistance
} = require('../../utils/store');
const location = require('../../utils/location');

/**
 * 取首个分类 id：菜单为空、分组为空或分类为空时统一返回 ''。
 *
 * 菜单来自接口，可能出现「门店已上架但菜单未就绪 / 该门店下架全部商品」，
 * 此时 applyListing 会把空分组整组过滤掉，activeMenu.groups 为空数组。
 * 这里必须做完整防御，否则首屏与下拉刷新会抛
 * `Cannot read properties of undefined (reading 'categories')`。
 */
function getFirstCategoryId(menu) {
  const firstGroup = menu && menu.groups && menu.groups[0];
  const firstCategory = firstGroup && firstGroup.categories && firstGroup.categories[0];
  return firstCategory ? firstCategory.id : '';
}

/** 取首个分组 id：无分组时返回 ''，避免 activeMenu.groups[0] 越界。 */
function getFirstGroupId(menu) {
  const firstGroup = menu && menu.groups && menu.groups[0];
  return firstGroup ? firstGroup.id : '';
}

/** 由分类反查所属分组 id；菜单为空时返回 ''，避免菜单未就绪时点击分类崩溃。 */
function getGroupIdByCategoryId(menu, categoryId) {
  const groups = (menu && menu.groups) || [];
  const group = groups.find(item => (item.categories || []).some(category => category.id === categoryId));
  if (group) return group.id;
  return groups[0] ? groups[0].id : '';
}

function findProductById(menus, productId) {
  for (const menu of menus) {
    for (const group of menu.groups) {
      for (const category of group.categories) {
        const product = category.products.find(item => item.id === productId);
        if (product) return product;
      }
    }
  }
  return null;
}

function buildProductRows(activeMenu) {
  const rows = [];
  (activeMenu.groups || []).forEach(group => {
    (group.categories || []).forEach(category => {
      rows.push({ type: 'category', id: 'anchor-' + category.id, label: category.label });
      const products = category.products || [];
      products.forEach((product, index) => {
        rows.push({
          type: 'product',
          id: 'product-' + product.id,
          // 后端商品金额为「分」且字段扁平，统一经 normalizeSpecProduct 转为「元」+ specDetail，
          // 保证卡片与规格弹层使用同一份数据，避免出现 ¥1600 这类未换算的价格。
          product: normalizeSpecProduct(product),
          showDivider: index < products.length - 1
        });
      });
    });
  });
  return rows;
}

function summarizeCart(items) {
  const selectedItems = items.filter(item => item.selected);
  return {
    count: selectedItems.reduce((sum, item) => sum + item.quantity, 0),
    total: Math.round(selectedItems.reduce((sum, item) => sum + item.price * item.quantity, 0) * 10) / 10
  };
}

function applyFavoriteState(storeList, favoriteStoreIds) {
  return storeList.map(store =>
    Object.assign({}, store, {
      isFavorite: favoriteStoreIds.indexOf(store.id) !== -1
    })
  );
}

function filterStores(stores, keyword) {
  const normalizedKeyword = String(keyword || '')
    .trim()
    .toLowerCase();
  if (!normalizedKeyword) return stores;
  return stores.filter(store => `${store.name}${store.address}`.toLowerCase().indexOf(normalizedKeyword) !== -1);
}

Page(
  withShare({
    data: {
      currentStore: {},
      orderMode: 'pickup',
      activeMenu: {},
      selectedCategoryId: '',
      selectedGroupId: '',
      scrollIntoView: '',
      productRows: [],
      cartVisible: false,
      activityVisible: false,
      menuActivity: {},
      specVisible: false,
      specProduct: {},
      specMode: 'add',
      specInitialQuantity: 1,
      specInitialSelectedOptionIds: [],
      editingCartItemId: '',
      cartItems: [],
      cartCount: 0,
      cartTotal: 0,
      storePickerVisible: true,
      // 定位进行中标记：防止用户连点「重新定位」并发申请授权
      locating: false,
      pickerCityName: '长沙市',
      pickerCityCode: 'changsha',
      pickerAnchor: { latitude: 28.2282, longitude: 112.9388 },
      pickerLocation: { latitude: 28.2282, longitude: 112.9388 },
      pickerStores: [],
      pickerMarkers: [],
      pickerSearchKeyword: '',
      pickerHasCurrentStore: false,
      favoriteStoreIds: []
    },
    setTabBarHidden(hidden) {
      if (this.getTabBar) this.getTabBar().setData({ hidden });
    },
    /**
     * 点单页专用的轻量刷新：只拉「菜单 + 门店」。
     *
     * 按产品要求「进入哪个页面就刷新哪个接口」：点单页每次 onShow 只重新请求
     * 菜单与门店（变更最频繁），城市与会员等级仅在首屏 onLoad 拉取一次。
     * 这样既保证每次进入都能看到后台最新数据，又避免每次返回都发 4 个请求。
     */
    refreshMenuPage() {
      return Promise.all([refreshStoreCatalogFromRemote(), refreshMenuFromRemote()]).then(() => this.renderMenu());
    },
    /**
     * 菜单加载失败时的用户提示。
     *
     * 只在「镜像从未成功加载过」时提示：此时页面是空的，用户必须知道可以下拉重试；
     * 若只是某次刷新失败（已有旧镜像在展示），静默保留旧数据即可，不该打扰用户。
     */
    notifyMenuLoadFailure() {
      const sync = getMenuSyncState();
      if (sync.error && !sync.loaded) {
        wx.showToast({ title: '菜单加载失败，请下拉重试', icon: 'none' });
      }
    },
    /**
     * 下拉刷新：手动兜底。
     *
     * onShow 已保证每次进入都会刷新，但用户停留在页面上时后台改价 / 上下架不会自动同步，
     * 因此保留一个手动刷新入口。
     */
    onPullDownRefresh() {
      return this.refreshThenSync()
        .then(() => this.notifyMenuLoadFailure())
        .then(() => wx.stopPullDownRefresh());
    },
    onLoad() {
      // 首屏需要四类数据：城市（选店依赖）、门店、菜单、会员等级（规格弹层会员价依赖）。
      //
      // 这里只负责「把数据灌进内存镜像」，不负责渲染：
      // 小程序生命周期保证 onLoad 之后必然触发 onShow，而 onShow 已经统一走
      // refreshThenSync()（先 await 接口再 syncCurrentStore）。
      // 历史 bug：onLoad 自己也调 renderMenu()，于是和 onShow 的异步链并发渲染，
      // 谁后返回谁覆盖 setData —— 出现过「门店明明已缓存，门店选择层却又被打开」的竞态。
      Promise.all([
        refreshCitiesFromRemote(),
        refreshStoreCatalogFromRemote(),
        refreshMenuFromRemote(),
        refreshMemberLevelsFromRemote()
      ]);
      api
        .fetchHomeConfig()
        .then(cfg => {
          if (cfg && cfg.menuActivity) this.setData({ menuActivity: cfg.menuActivity });
        })
        .catch(() => null);
    },
    /** 按当前门店过滤菜单并渲染首屏。 */
    renderMenu() {
      const app = getApp();
      const catalog = resolveStoreCatalog();
      // 点单页不展示顶部页签：其余 TAB 的分组已并入首个 TAB，同级展示
      const activeMenu = getMergedMenuTab(catalog.currentStore && catalog.currentStore.id);
      // 门店/选择层数据与菜单是否就绪无关，必须先算出来。
      // 历史 bug：这段原本写在「有菜单」分支里，菜单为空时提前 return 会连带跳过，
      // 导致门店选择层拿不到 pickerStores（列表空白），用户无法选门店自救。
      const catalogUpdates = this.buildCatalogUpdates(catalog, { openPicker: !catalog.currentStore });
      const cartUpdates = this.buildCartUpdates(catalog.currentStore && catalog.currentStore.id);
      const base = { orderMode: app.globalData.orderMode };
      if (!activeMenu || !activeMenu.groups.length) {
        // 无菜单时清空菜单相关字段，避免残留上一次的 activeMenu / 分类选中态 / 商品行；
        // 但门店与购物车数据照常写入，否则门店选择层会变成空列表。
        this.setData(
          Object.assign(
            base,
            {
              activeMenu: { groups: [] },
              selectedCategoryId: '',
              selectedGroupId: '',
              scrollIntoView: '',
              productRows: []
            },
            catalogUpdates,
            cartUpdates
          )
        );
        return;
      }
      this.setData(
        Object.assign(
          base,
          {
            activeMenu,
            selectedCategoryId: getFirstCategoryId(activeMenu),
            selectedGroupId: getFirstGroupId(activeMenu),
            scrollIntoView: '',
            productRows: buildProductRows(activeMenu)
          },
          catalogUpdates,
          cartUpdates
        )
      );
    },
    /**
     * onShow 统一入口：先向后端重新拉取「菜单 + 门店」，成功后再基于新数据同步渲染。
     *
     * 必须是「先 await 接口、再 syncCurrentStore」：
     * 若先同步渲染再拉取，会先用旧镜像渲染一帧，用户会看到数据闪回旧值。
     */
    refreshThenSync() {
      return this.refreshMenuPage().then(() => this.syncCurrentStore());
    },
    /**
     * 页面展示统一入口。
     *
     * 进入点单页的路径有很多（冷启动 / 切换 Tab / 从收藏、城市、下单页返回），
     * 它们**必须走同一条数据链路**：先向后端拉「门店 + 菜单」，拿到新数据后再渲染。
     *
     * 为什么要把分支全部收敛掉（历史 bug）：
     *   · 旧实现里「无缓存门店」那条分支只调 openStorePicker()，一个请求都不发，
     *     但底下的 renderMenu 已经用「未按门店过滤」的兜底数据画了一版菜单，
     *     表现为「门店还在选，菜单却先出来了，而且不走接口」；
     *   · 各分支时序不一致，容易出现先渲染旧镜像再闪回新数据。
     *
     * 现在只保留一个入口：refreshThenSync() 内部先 await 接口再 syncCurrentStore，
     * 门店选择层不再单独打开，而是由 buildCatalogUpdates 里
     * `openPicker: !catalog.currentStore` 按「有没有可用门店」自动决定（无门店时自动弹出）。
     * 这样「没有门店」也不会出现「不请求接口」的空档。
     */
    onShow() {
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
      this.setTabBarHidden(false);
      // 清掉一次性来源标记：门店层开合已由 catalog 状态决定，不再依赖它分支
      this.returningFrom = '';
      this.refreshThenSync().then(() => this.notifyMenuLoadFailure());
    },
    /** 点击当前 Tab（已在点单页）时同样走统一入口，保证每次点进来都刷新。 */
    onTabItemTap() {
      this.onShow();
    },
    buildCatalogUpdates(catalog, options = {}) {
      const favoriteStoreIds = getFavoriteStoreIds();
      const pickerStores = applyFavoriteState(catalog.stores || [], favoriteStoreIds);
      const currentStore = catalog.currentStore
        ? pickerStores.find(store => store.id === catalog.currentStore.id) || {}
        : {};
      // 城市数据来自接口，拉取失败时降级为空，避免页面崩溃
      const city = catalog.city || { name: '', code: '', latitude: 0, longitude: 0 };
      const origin = catalog.origin || city;
      const updates = {
        pickerCityName: city.name,
        pickerCityCode: city.code,
        pickerAnchor: {
          latitude: currentStore.latitude || city.latitude,
          longitude: currentStore.longitude || city.longitude
        },
        // 真实距离计算原点：优先用户定位，无定位时回落城市中心
        pickerLocation: {
          latitude: origin.latitude || city.latitude,
          longitude: origin.longitude || city.longitude
        },
        pickerStores,
        pickerMarkers: buildStoreMarkers(
          pickerStores,
          catalog.currentStore ? catalog.currentStore.id : null
        ),
        pickerHasCurrentStore: Boolean(catalog.currentStore),
        currentStore,
        favoriteStoreIds
      };
      // storePickerVisible 必须双向显式赋值：
      // 页面 data 里的初值是 true（首屏无门店时直接展示选择层），
      // 若这里只在 openPicker 为真时赋值、为假时不赋值，就会出现
      // 「已选好门店、商品列表也出来了，门店选择框却还留着」的串台状态。
      updates.storePickerVisible = Boolean(options.openPicker);
      return updates;
    },
    syncGlobals(catalog) {
      const app = getApp();
      app.globalData.selectedStoreId = catalog.currentStore ? catalog.currentStore.id : null;
      app.globalData.selectedCityCode = catalog.city.code;
      app.globalData.selectedCityName = catalog.city.name;
    },
    openStorePicker(resetSearch = true) {
      const catalog = resolveStoreCatalog();
      const updates = this.buildCatalogUpdates(catalog, { openPicker: true });
      updates.pickerSearchKeyword = resetSearch ? '' : this.data.pickerSearchKeyword;
      updates.pickerStores = filterStores(updates.pickerStores, updates.pickerSearchKeyword);
      this.syncGlobals(catalog);
      this.setData(updates);
      // 门店页是 Tab 页的无门店态：TabBar 常显，列表末尾用 tabbar-safe-space 兜底
      this.setTabBarHidden(false);
      // 先渲染直线距离，再异步替换为服务端真实驾车距离
      this.refreshPickerRealDistance();
    },
    /**
     * 用服务端真实驾车距离刷新门店列表。
     *
     * 为什么异步后置：真实距离需请求服务端代理腾讯接口，若阻塞在打开选择层之前，
     * 用户会先看到一段空白。这里先渲染直线距离，拿到真实距离后再原地刷新。
     *
     * 降级：未配置密钥 / 腾讯失败 / 请求异常时静默保留直线距离，不打断用户。
     */
    refreshPickerRealDistance() {
      const updates = this.data.pickerStores || [];
      if (!updates.length) return;
      const origin = this.data.pickerLocation || null;
      if (!origin || !origin.latitude || !origin.longitude) return;
      const stores = updates;
      api
        .fetchStoreDistances(origin.latitude, origin.longitude, stores)
        .then(distances => {
          if (!Array.isArray(distances) || !distances.length) return;
          // 请求返回时用户可能已关层或换城，这里校验后再刷新
          if (!this.data.storePickerVisible) return;
          const refreshed = decorateStoresWithRealDistance(stores, origin, distances);
          this.setData({ pickerStores: refreshed });
        })
        .catch(() => {
          // 静默降级：保留直线距离
        });
    },
    /** 查看门店地图：跳独立地图页（Skyline 不支持原生 map） */
    openStoreMap() {
      wx.navigateTo({ url: '/pages/store-map/store-map' });
    },
    /** 点击地图标记：把地图中心移到该门店 */
    handlePickerMarkerTap(event) {
      const markerId = event && event.detail && event.detail.markerId;
      const marker = (this.data.pickerMarkers || []).find(item => item.id === markerId);
      if (!marker) return;
      this.setData({
        pickerAnchor: { latitude: marker.latitude, longitude: marker.longitude }
      });
    },
    closeStorePicker() {
      if (!this.data.pickerHasCurrentStore) {
        wx.switchTab({ url: '/pages/home/home' });
        return;
      }
      this.setData({ storePickerVisible: false });
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
      this.setTabBarHidden(false);
    },
    handlePickerSearch(event) {
      const pickerSearchKeyword = event.detail.value;
      const pickerStores = applyFavoriteState(resolveStoreCatalog().stores, getFavoriteStoreIds());
      this.setData({
        pickerSearchKeyword,
        pickerStores: filterStores(pickerStores, pickerSearchKeyword)
      });
    },
    clearPickerSearch() {
      const pickerStores = applyFavoriteState(resolveStoreCatalog().stores, getFavoriteStoreIds());
      this.setData({ pickerSearchKeyword: '', pickerStores });
    },
    /**
     * 门店选择层里选中门店。
     *
     * 必须「先落库 + 立刻关层（给用户即时反馈），再拉该门店的菜单」：
     * 旧实现只 setData 换掉 currentStore 就结束了，不发任何请求，
     * 于是切门店后菜单仍是上一家门店的商品与上下架状态 —— 这是真实 bug。
     * 这里复用 refreshThenSync()，拉到新数据后再 syncCurrentStore 覆盖渲染。
     */
    handleSelectPickerStore(event) {
      const { id } = event.detail.store;
      persistSelectedStore(id);
      const catalog = resolveStoreCatalog();
      const updates = this.buildCatalogUpdates(catalog, { openPicker: false });
      updates.storePickerVisible = false;
      this.syncGlobals(catalog);
      // 先关层并切到新门店名，避免用户等待接口时看着旧门店名
      this.setData(updates);
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
      this.setTabBarHidden(false);
      // 再拉新门店的菜单；失败时 refreshMenuFromRemote 会保留旧镜像，不会白屏
      this.refreshThenSync();
    },
    handleStorePhone(event) {
      const { phone } = event.detail.store;
      if (!phone) return;
      wx.makePhoneCall({ phoneNumber: phone });
    },
    handleStoreNavigate(event) {
      const store = event.detail.store;
      if (!store) return;
      wx.openLocation({
        latitude: store.latitude,
        longitude: store.longitude,
        name: store.name,
        address: store.address,
        scale: 16
      });
    },
    /**
     * 重新定位：申请 scope.userLocation -> wx.getLocation -> 逆地理 -> 刷新门店排序。
     *
     * 降级：拒绝授权 / 定位失败时回落到城市中心坐标，仍能正常排序（不阻断用户）。
     * 真实驾车距离随后由 refreshPickerRealDistance 异步补齐。
     */
    handleLocate() {
      if (this.data.locating) return;
      this.setData({ locating: true });
      wx.showLoading({ title: '定位中', mask: true });
      location
        .locate({ force: true })
        .then(result => {
          wx.hideLoading();
          this.setData({ locating: false });
          const catalog = resolveStoreCatalog();
          this.syncGlobals(catalog);
          this.setData(this.buildCatalogUpdates(catalog, { openPicker: true }));
          if (result && result.source === location.LOCATION_SOURCE.DEVICE) {
            wx.showToast({ title: `已定位到${result.cityName || '当前位置'}`, icon: 'none' });
            this.refreshPickerRealDistance();
            return;
          }
          // 未拿到设备坐标：说明用户拒绝或定位失败，明确提示并提供去设置入口
          wx.showToast({ title: '未开启定位，已按城市中心排序', icon: 'none' });
        })
        .catch(() => {
          wx.hideLoading();
          this.setData({ locating: false });
          wx.showToast({ title: '定位失败，请稍后重试', icon: 'none' });
        });
    },
    selectCity() {
      this.returningFrom = 'city';
      wx.navigateTo({ url: `/pages/city-picker/city-picker?city=${this.data.pickerCityCode}` });
    },
    openFavoriteStores() {
      this.returningFrom = 'favorites';
      getApp().globalData.favoriteStoreSelected = false;
      wx.navigateTo({ url: '/pages/favorite-stores/favorite-stores' });
    },
    handleCurrentFavorite() {
      const storeId = this.data.currentStore && this.data.currentStore.id;
      if (!storeId) {
        wx.showToast({ title: '请先选择门店', icon: 'none' });
        return;
      }
      const result = toggleFavoriteStore(storeId);
      const catalog = resolveStoreCatalog();
      const updates = this.buildCatalogUpdates(catalog, { openPicker: false });
      updates.storePickerVisible = this.data.storePickerVisible;
      this.setData(updates);
      wx.showToast({ title: result.favorite ? '已收藏' : '已取消收藏', icon: 'none' });
    },
    /**
     * 基于最新门店/菜单数据同步渲染。
     *
     * 关键：storePickerVisible 不能写死 false。
     * 历史 bug：门店选择有效期是 30 分钟（见 utils/store.js 的 STORE_SELECTION_TTL），
     * 用户退后台超过 30 分钟再回来时 activeStoreId 会被清空，此时若强行把门店层设为不可见，
     * 就会出现「顶部门店信息是空的（currentStore = {}）+ 商品列表照旧渲染」的错乱界面 ——
     * 既没有门店信息，也没有入口去选门店。
     * 正确做法：沿用 buildCatalogUpdates 里 openPicker: !catalog.currentStore 的判定，
     * 没有可用门店就自动弹出选择层，让用户重新选。
     */
    syncCurrentStore() {
      const catalog = resolveStoreCatalog();
      const updates = this.buildCatalogUpdates(catalog, { openPicker: !catalog.currentStore });
      this.syncGlobals(catalog);
      Object.assign(updates, this.buildListingUpdates(catalog.currentStore));
      this.setData(updates);
    },
    // 按当前门店过滤菜单：下架商品不出现在点单页，空分类 / 空分组 / 空 Tab 自动隐藏。
    buildListingUpdates(store) {
      const storeId = store && store.id;
      // getMergedMenuTab 在「菜单接口未返回 / 该门店商品全部下架」时返回 null，
      // 此时必须回落到带空 groups 的菜单骨架，否则下面读 groups[0].id 会崩溃。
      const activeMenu = getMergedMenuTab(storeId) || { groups: [] };
      return Object.assign(
        {
          activeMenu,
          selectedCategoryId: getFirstCategoryId(activeMenu),
          selectedGroupId: getFirstGroupId(activeMenu),
          // 无菜单时不保留旧的滚动锚点，避免 scrollIntoView 指向已不存在的锚点
          scrollIntoView: activeMenu.groups.length ? this.data.scrollIntoView : '',
          productRows: buildProductRows(activeMenu)
        },
        this.buildCartUpdates(storeId)
      );
    },
    // 购物车里的已下架商品仅做标记，不自动移除，由用户自行处理。
    buildCartUpdates(storeId) {
      const cartItems = (this.data.cartItems || []).map(item =>
        Object.assign({}, item, { listed: isProductListed(storeId, item.productId) })
      );
      const summary = summarizeCart(cartItems);
      return {
        cartItems,
        cartCount: summary.count,
        cartTotal: summary.total,
        hasUnlistedInCart: cartItems.some(item => item.selected && item.listed === false)
      };
    },
    selectStore() {
      this.openStorePicker(true);
    },
    openActivitySheet() {
      if (!this.data.currentStore || !this.data.currentStore.id) {
        wx.showToast({ title: '请先选择门店', icon: 'none' });
        return;
      }
      this.setTabBarHidden(true);
      this.setData({
        activityVisible: true,
        cartVisible: false,
        specVisible: false
      });
    },
    closeActivitySheet() {
      this.setTabBarHidden(false);
      this.setData({ activityVisible: false });
    },
    openActivityRules() {
      this.closeActivitySheet();
      wx.navigateTo({ url: '/pages/activity-rules/activity-rules' });
    },
    handleActivityPhone() {
      const phone = this.data.currentStore && this.data.currentStore.phone;
      if (!phone) {
        wx.showToast({ title: '门店电话暂未配置', icon: 'none' });
        return;
      }
      wx.makePhoneCall({ phoneNumber: phone });
    },
    handleActivityUnavailable(event) {
      const label = event.detail.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    },
    handleSpec(event) {
      this.setTabBarHidden(true);
      this.setData({
        specVisible: true,
        specProduct: normalizeSpecProduct(event.detail.product),
        specMode: 'add',
        specInitialQuantity: 1,
        specInitialSelectedOptionIds: [],
        editingCartItemId: '',
        cartVisible: false
      });
    },
    closeSpec() {
      this.setTabBarHidden(false);
      this.setData({
        specVisible: false,
        specMode: 'add',
        specInitialQuantity: 1,
        specInitialSelectedOptionIds: [],
        editingCartItemId: ''
      });
    },
    handleSpecAddCart(event) {
      const { product, selectedOptions, quantity, unitPrice, originalPrice, storedValuePrice, storedValueDiscount, specText } = event.detail;
      const selectedOptionIds = selectedOptions.map(option => option.id);
      const cartId = buildCartId(product.id, selectedOptionIds);
      const cartItems = this.data.cartItems.map(item => ({ ...item }));
      const existing = cartItems.find(item => item.id === cartId);

      if (existing) {
        existing.quantity += quantity;
      } else {
        cartItems.push({
          id: cartId,
          productId: product.id,
          selectedOptionIds,
          name: product.name,
          spec: specText,
          price: unitPrice,
          originalPrice,
          storedValuePrice: storedValuePrice || 0,
          storedValueDiscount: storedValueDiscount || 0,
          quantity,
          selected: true,
          listed: true,
          image: product.image
        });
      }

      const summary = summarizeCart(cartItems);
      this.setTabBarHidden(false);
      this.setData({
        cartItems,
        cartCount: summary.count,
        cartTotal: summary.total,
        specVisible: false
      });
      wx.showToast({ title: '已加入购物车', icon: 'none' });
    },
    handleSpecBuy() {
      wx.showToast({ title: '立即购买暂未接入', icon: 'none' });
    },
    handleSpecFavorite(event) {
      wx.showToast({ title: event.detail.favorite ? '已收藏' : '已取消收藏', icon: 'none' });
    },
    handleCart() {
      if (!this.data.cartItems.length) return;
      this.setData({ cartVisible: !this.data.cartVisible });
    },
    closeCart() {
      this.setTabBarHidden(false);
      this.setData({ cartVisible: false });
    },
    handleCartChange(event) {
      const cartItems = event.detail.items || [];
      const summary = summarizeCart(cartItems);
      this.setData({
        cartItems,
        cartCount: summary.count,
        cartTotal: summary.total,
        cartVisible: cartItems.length > 0
      });
    },
    handleCartEdit(event) {
      const cartItem = this.data.cartItems.find(item => item.id === event.detail.id);
      if (!cartItem || !cartItem.productId) {
        wx.showToast({ title: '商品规格暂不可编辑', icon: 'none' });
        return;
      }
      const product = normalizeSpecProduct(findProductById([this.data.activeMenu], cartItem.productId));
      if (!product || !product.id) {
        wx.showToast({ title: '商品规格暂不可编辑', icon: 'none' });
        return;
      }
      this.setTabBarHidden(true);
      this.setData({
        specProduct: product,
        specMode: 'edit',
        specInitialQuantity: cartItem.quantity,
        specInitialSelectedOptionIds: cartItem.selectedOptionIds || [],
        editingCartItemId: cartItem.id,
        specVisible: true,
        cartVisible: true
      });
    },
    handleCartUpdate(event) {
      this.setTabBarHidden(false);
      const cartItems = mergeEditedCartItem(this.data.cartItems, this.data.editingCartItemId, event.detail);
      const summary = summarizeCart(cartItems);
      this.setData({
        cartItems,
        cartCount: summary.count,
        cartTotal: summary.total,
        specVisible: false,
        specMode: 'add',
        specInitialQuantity: 1,
        specInitialSelectedOptionIds: [],
        editingCartItemId: '',
        cartVisible: true
      });
    },
    handleCartInfo() {
      wx.showToast({ title: '优惠说明暂未接入', icon: 'none' });
    },
    handleCheckout() {
      const selectedItems = this.data.cartItems.filter(item => item.selected);
      if (!summarizeCart(this.data.cartItems).count) {
        wx.showToast({ title: '请选择商品', icon: 'none' });
        return;
      }
      if (this.data.hasUnlistedInCart) {
        wx.showToast({ title: '已选商品中含已下架商品，请先移除', icon: 'none' });
        return;
      }
      this.returningFrom = 'order';
      getApp().globalData.pendingOrder = {
        items: selectedItems,
        storeId: this.data.currentStore.id,
        orderMode: this.data.orderMode
      };
      wx.navigateTo({ url: '/pages/order-confirm/order-confirm' });
    },
    setOrderMode(event) {
      const { mode } = event.currentTarget.dataset;
      if (!this.data.currentStore || (this.data.currentStore.modes || []).indexOf(mode) === -1) {
        wx.showToast({ title: '当前门店暂不支持该方式', icon: 'none' });
        return;
      }
      getApp().globalData.orderMode = mode;
      this.setData({ orderMode: mode });
    },
    selectCategory(event) {
      const { id } = event.currentTarget.dataset;
      this.setData({
        selectedCategoryId: id,
        selectedGroupId: getGroupIdByCategoryId(this.data.activeMenu, id),
        scrollIntoView: 'anchor-' + id
      });
    },
    showUnavailable(event) {
      const label = event.currentTarget.dataset.label || '功能';
      wx.showToast({ title: `${label}暂未接入`, icon: 'none' });
    }
  })
);