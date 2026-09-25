const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getListedMenuTabs, isProductListed, refreshMenuFromRemote, getMenuCatalog } = require('../../utils/product-listing');
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

function getFirstCategoryId(menu) {
  return menu.groups[0].categories[0].id;
}

function getGroupIdByCategoryId(menu, categoryId) {
  const group = menu.groups.find(item => item.categories.some(category => category.id === categoryId));
  return group ? group.id : menu.groups[0].id;
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
      menuTabs: [],
      activeMenuIndex: 0,
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
    onLoad() {
      // 菜单 / 活动 / 门店均来自后端接口。
      // 顺序敏感：必须等门店与菜单都就绪后再渲染，否则会先渲染出空门店列表。
      Promise.all([
        refreshCitiesFromRemote(),
        refreshStoreCatalogFromRemote().then(() => {
          if (typeof this.syncCurrentStore === 'function' && resolveStoreCatalog().currentStore) {
            this.syncCurrentStore();
          }
        }),
        refreshMenuFromRemote(),
        // 规格弹层的会员价依赖会员等级折扣，必须先就绪，否则会员价会等于原价
        refreshMemberLevelsFromRemote()
      ]).then(() => this.renderMenu());
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
      const menus = getListedMenuTabs(catalog.currentStore && catalog.currentStore.id);
      if (!menus.length) {
        this.setData({ menuTabs: [], activeMenu: {}, selectedCategoryId: '', selectedGroupId: '' });
        return;
      }
      const activeMenuIndex = Math.max(0, menus.findIndex(item => item.id === app.globalData.menuTabId));
      const activeMenu = menus[activeMenuIndex];
      this.setData(
        Object.assign(
          {
            orderMode: app.globalData.orderMode,
            menuTabs: menus,
            activeMenuIndex,
            activeMenu,
            selectedCategoryId: getFirstCategoryId(activeMenu),
            selectedGroupId: activeMenu.groups[0].id,
            scrollIntoView: '',
            productRows: buildProductRows(activeMenu)
          },
          this.buildCatalogUpdates(catalog, { openPicker: !catalog.currentStore }),
          this.buildCartUpdates(catalog.currentStore && catalog.currentStore.id)
        )
      );
    },
    onShow() {
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
      this.setTabBarHidden(false);
      if (this.returningFrom === 'favorites') {
        this.returningFrom = '';
        const app = getApp();
        const selected = Boolean(app.globalData.favoriteStoreSelected);
        app.globalData.favoriteStoreSelected = false;
        if (selected) {
          this.syncCurrentStore();
          return;
        }
        this.setData({ storePickerVisible: true });
        // 门店页 TabBar 常显，无需隐藏
        this.setTabBarHidden(false);
        return;
      }
      if (this.returningFrom === 'city') {
        this.returningFrom = '';
        const cityCatalog = resolveStoreCatalog();
        if (cityCatalog.currentStore) {
          this.syncCurrentStore();
          return;
        }
        this.openStorePicker(false);
        return;
      }
      if (this.returningFrom === 'order') {
        this.returningFrom = '';
        this.syncCurrentStore();
        return;
      }
      const catalog = resolveStoreCatalog();
      if (catalog.currentStore) {
        this.syncCurrentStore();
        if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
        this.setTabBarHidden(false);
        return;
      }
      this.openStorePicker(true);
    },
    onTabItemTap() {
      this.returningFrom = '';
      const catalog = resolveStoreCatalog();
      if (catalog.currentStore) {
        this.syncCurrentStore();
        if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
        this.setTabBarHidden(false);
        return;
      }
      this.openStorePicker(true);
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
      if (options.openPicker) updates.storePickerVisible = true;
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
    handleSelectPickerStore(event) {
      const { id } = event.detail.store;
      persistSelectedStore(id);
      const catalog = resolveStoreCatalog();
      const updates = this.buildCatalogUpdates(catalog, { openPicker: false });
      updates.storePickerVisible = false;
      this.syncGlobals(catalog);
      this.setData(updates);
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 });
      this.setTabBarHidden(false);
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
    syncCurrentStore() {
      const catalog = resolveStoreCatalog();
      const updates = this.buildCatalogUpdates(catalog, { openPicker: false });
      updates.storePickerVisible = false;
      this.syncGlobals(catalog);
      Object.assign(updates, this.buildListingUpdates(catalog.currentStore));
      this.setData(updates);
    },
    // 按当前门店过滤菜单：下架商品不出现在点单页，空分类 / 空分组 / 空 Tab 自动隐藏。
    buildListingUpdates(store) {
      const storeId = store && store.id;
      const menus = getListedMenuTabs(storeId);
      const activeMenuIndex = Math.max(
        0,
        menus.findIndex(item => item.id === (menus[this.data.activeMenuIndex] || {}).id)
      );
      const activeMenu = menus[Math.min(activeMenuIndex, menus.length - 1)] || menus[0];
      const catalogMenus = this.buildCartUpdates(storeId);
      return Object.assign(
        {
          menuTabs: menus,
          activeMenuIndex,
          activeMenu,
          selectedCategoryId: getFirstCategoryId(activeMenu),
          selectedGroupId: activeMenu.groups[0].id
        },
        catalogMenus
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
      const product = normalizeSpecProduct(findProductById(this.data.menuTabs, cartItem.productId));
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
    selectMenuTab(event) {
      const activeMenuIndex = Number(event.currentTarget.dataset.index);
      const activeMenu = this.data.menuTabs[activeMenuIndex];
      const selectedCategoryId = getFirstCategoryId(activeMenu);
      getApp().globalData.menuTabId = activeMenu.id;
      this.setData({
        activeMenuIndex,
        activeMenu,
        selectedCategoryId,
        selectedGroupId: activeMenu.groups[0].id,
        scrollIntoView: 'product-top',
        productRows: buildProductRows(activeMenu)
      });
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

