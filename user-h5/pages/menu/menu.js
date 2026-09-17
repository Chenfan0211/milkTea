const { withShare } = require('../../utils/share')
const { initialCartItems, menuActivity, menuTabs } = require('../../data/mock')
const { buildCartId, mergeEditedCartItem } = require('../../utils/cart')
const {
  getFavoriteStoreIds,
  resolveStoreCatalog,
  selectStore: persistSelectedStore,
  toggleFavoriteStore,
  useDeviceLocation
} = require('../../utils/store')

function getFirstCategoryId(menu) {
  return menu.groups[0].categories[0].id
}

function getGroupIdByCategoryId(menu, categoryId) {
  const group = menu.groups.find(item => item.categories.some(category => category.id === categoryId))
  return group ? group.id : menu.groups[0].id
}

function findProductById(menus, productId) {
  for (const menu of menus) {
    for (const group of menu.groups) {
      for (const category of group.categories) {
        const product = category.products.find(item => item.id === productId)
        if (product) return product
      }
    }
  }
  return null
}

function summarizeCart(items) {
  const selectedItems = items.filter(item => item.selected)
  return {
    count: selectedItems.reduce((sum, item) => sum + item.quantity, 0),
    total: Math.round(selectedItems.reduce((sum, item) => sum + item.price * item.quantity, 0) * 10) / 10
  }
}

function buildMarkers(stores, currentStoreId) {
  return stores.map((store, index) => {
    const active = store.id === currentStoreId
    return {
      id: index + 1,
      storeId: store.id,
      latitude: store.latitude,
      longitude: store.longitude,
      iconPath: active
        ? '/assets/icons/lucide/store-marker-active.svg'
        : '/assets/icons/lucide/store-marker.svg',
      width: 36,
      height: 36,
      zIndex: active ? 3 : 2,
      anchor: { x: 0.5, y: 0.5 },
      callout: active
        ? { content: store.name, display: 'ALWAYS', textAlign: 'center' }
        : undefined
    }
  })
}

function applyFavoriteState(storeList, favoriteStoreIds) {
  return storeList.map(store => Object.assign({}, store, {
    isFavorite: favoriteStoreIds.indexOf(store.id) !== -1
  }))
}

function filterStores(stores, keyword) {
  const normalizedKeyword = String(keyword || '').trim().toLowerCase()
  if (!normalizedKeyword) return stores
  return stores.filter(store => `${store.name}${store.address}`.toLowerCase().indexOf(normalizedKeyword) !== -1)
}

Page(withShare({
  data: {
    currentStore: {},
    orderMode: 'pickup',
    menuTabs,
    activeMenuIndex: 0,
    activeMenu: menuTabs[0],
    selectedCategoryId: getFirstCategoryId(menuTabs[0]),
    selectedGroupId: menuTabs[0].groups[0].id,
    scrollIntoView: '',
    cartVisible: false,
    activityVisible: false,
    menuActivity,
    specVisible: false,
    specProduct: {},
    specMode: 'add',
    specInitialQuantity: 1,
    specInitialSelectedOptionIds: [],
    editingCartItemId: '',
    cartItems: initialCartItems,
    cartCount: summarizeCart(initialCartItems).count,
    cartTotal: summarizeCart(initialCartItems).total,
    storePickerVisible: true,
    pickerCityName: '长沙市',
    pickerCityCode: 'changsha',
    pickerAnchor: { latitude: 28.2282, longitude: 112.9388 },
    pickerStores: [],
    pickerSearchKeyword: '',
    mapMarkers: [],
    pickerHasCurrentStore: false,
    favoriteStoreIds: []
  },
  setTabBarHidden(hidden) {
    if (this.getTabBar) this.getTabBar().setData({ hidden })
  },
  onLoad() {
    const app = getApp()
    const activeMenuIndex = Math.max(0, menuTabs.findIndex(item => item.id === app.globalData.menuTabId))
    const activeMenu = menuTabs[activeMenuIndex]
    const catalog = resolveStoreCatalog()
    this.setData(Object.assign({
      orderMode: app.globalData.orderMode,
      activeMenuIndex,
      activeMenu,
      selectedCategoryId: getFirstCategoryId(activeMenu),
      selectedGroupId: activeMenu.groups[0].id,
      scrollIntoView: ''
    }, this.buildCatalogUpdates(catalog, { openPicker: !catalog.currentStore })))
  },
  onShow() {
    if (this.getTabBar) this.getTabBar().setData({ selected: 1 })
    this.setTabBarHidden(false)
    if (this.returningFrom === 'favorites') {
      this.returningFrom = ''
      const app = getApp()
      const selected = Boolean(app.globalData.favoriteStoreSelected)
      app.globalData.favoriteStoreSelected = false
      if (selected) {
        this.syncCurrentStore()
        return
      }
      this.setData({ storePickerVisible: true })
      this.setTabBarHidden(true)
      return
    }
    if (this.returningFrom === 'city') {
      this.returningFrom = ''
      const cityCatalog = resolveStoreCatalog()
      if (cityCatalog.currentStore) {
        this.syncCurrentStore()
        return
      }
      this.openStorePicker(false)
      return
    }
    if (this.returningFrom === 'order') {
      this.returningFrom = ''
      this.syncCurrentStore()
      return
    }
    const catalog = resolveStoreCatalog()
    if (catalog.currentStore) {
      this.syncCurrentStore()
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 })
      this.setTabBarHidden(false)
      return
    }
    this.openStorePicker(true)
  },
  onTabItemTap() {
    this.returningFrom = ''
    const catalog = resolveStoreCatalog()
    if (catalog.currentStore) {
      this.syncCurrentStore()
      if (this.getTabBar) this.getTabBar().setData({ selected: 1 })
      this.setTabBarHidden(false)
      return
    }
    this.openStorePicker(true)
  },
  buildCatalogUpdates(catalog, options = {}) {
    const favoriteStoreIds = getFavoriteStoreIds()
    const pickerStores = applyFavoriteState(catalog.stores, favoriteStoreIds)
    const currentStore = catalog.currentStore
      ? pickerStores.find(store => store.id === catalog.currentStore.id) || {}
      : {}
    const updates = {
      pickerCityName: catalog.city.name,
      pickerCityCode: catalog.city.code,
      pickerAnchor: {
        latitude: currentStore.latitude || catalog.city.latitude,
        longitude: currentStore.longitude || catalog.city.longitude
      },
      pickerStores,
      mapMarkers: buildMarkers(catalog.stores, currentStore.id),
      pickerHasCurrentStore: Boolean(catalog.currentStore),
      currentStore,
      favoriteStoreIds
    }
    if (options.openPicker) updates.storePickerVisible = true
    return updates
  },
  syncGlobals(catalog) {
    const app = getApp()
    app.globalData.selectedStoreId = catalog.currentStore ? catalog.currentStore.id : null
    app.globalData.selectedCityCode = catalog.city.code
    app.globalData.selectedCityName = catalog.city.name
  },
  openStorePicker(resetSearch = true) {
    const catalog = resolveStoreCatalog()
    const updates = this.buildCatalogUpdates(catalog, { openPicker: true })
    updates.pickerSearchKeyword = resetSearch ? '' : this.data.pickerSearchKeyword
    updates.pickerStores = filterStores(updates.pickerStores, updates.pickerSearchKeyword)
    this.syncGlobals(catalog)
    this.setData(updates)
    this.setTabBarHidden(true)
  },
  closeStorePicker() {
    if (!this.data.pickerHasCurrentStore) {
      wx.switchTab({ url: '/pages/home/home' })
      return
    }
    this.setData({ storePickerVisible: false })
    if (this.getTabBar) this.getTabBar().setData({ selected: 1 })
    this.setTabBarHidden(false)
  },
  handlePickerSearch(event) {
    const pickerSearchKeyword = event.detail.value
    const pickerStores = applyFavoriteState(resolveStoreCatalog().stores, getFavoriteStoreIds())
    this.setData({
      pickerSearchKeyword,
      pickerStores: filterStores(pickerStores, pickerSearchKeyword)
    })
  },
  clearPickerSearch() {
    const pickerStores = applyFavoriteState(resolveStoreCatalog().stores, getFavoriteStoreIds())
    this.setData({ pickerSearchKeyword: '', pickerStores })
  },
  handleMarkerTap(event) {
    const markerId = Number(event.detail.markerId)
    const marker = this.data.mapMarkers.find(item => item.id === markerId)
    if (!marker) return
    const store = resolveStoreCatalog().stores.find(item => item.id === marker.storeId)
    if (!store) return
    this.setData({ pickerAnchor: { latitude: store.latitude, longitude: store.longitude } })
  },
  handleSelectPickerStore(event) {
    const { id } = event.currentTarget.dataset
    persistSelectedStore(id)
    const catalog = resolveStoreCatalog()
    const updates = this.buildCatalogUpdates(catalog, { openPicker: false })
    updates.storePickerVisible = false
    this.syncGlobals(catalog)
    this.setData(updates)
    if (this.getTabBar) this.getTabBar().setData({ selected: 1 })
    this.setTabBarHidden(false)
  },
  handleStorePhone(event) {
    const { phone } = event.currentTarget.dataset
    if (!phone) return
    wx.makePhoneCall({ phoneNumber: phone })
  },
  handleStoreNavigate(event) {
    const { id } = event.currentTarget.dataset
    const store = this.data.pickerStores.find(item => item.id === id)
    if (!store) return
    wx.openLocation({
      latitude: store.latitude,
      longitude: store.longitude,
      name: store.name,
      address: store.address,
      scale: 16
    })
  },
  handleLocate() {
    const result = useDeviceLocation()
    const catalog = resolveStoreCatalog()
    this.syncGlobals(catalog)
    this.setData(this.buildCatalogUpdates(catalog, { openPicker: true }))
    wx.showToast({ title: `已定位到${result.city.name}`, icon: 'none' })
  },
  selectCity() {
    this.returningFrom = 'city'
    wx.navigateTo({ url: `/pages/city-picker/city-picker?city=${this.data.pickerCityCode}` })
  },
  openFavoriteStores() {
    this.returningFrom = 'favorites'
    getApp().globalData.favoriteStoreSelected = false
    wx.navigateTo({ url: '/pages/favorite-stores/favorite-stores' })
  },
  handleCurrentFavorite() {
    const storeId = this.data.currentStore && this.data.currentStore.id
    if (!storeId) {
      wx.showToast({ title: '请先选择门店', icon: 'none' })
      return
    }
    const result = toggleFavoriteStore(storeId)
    const catalog = resolveStoreCatalog()
    const updates = this.buildCatalogUpdates(catalog, { openPicker: false })
    updates.storePickerVisible = this.data.storePickerVisible
    this.setData(updates)
    wx.showToast({ title: result.favorite ? '已收藏' : '已取消收藏', icon: 'none' })
  },
  syncCurrentStore() {
    const catalog = resolveStoreCatalog()
    const updates = this.buildCatalogUpdates(catalog, { openPicker: false })
    updates.storePickerVisible = false
    this.syncGlobals(catalog)
    this.setData(updates)
  },
  selectStore() {
    this.openStorePicker(true)
  },
  openActivitySheet() {
    if (!this.data.currentStore || !this.data.currentStore.id) {
      wx.showToast({ title: '请先选择门店', icon: 'none' })
      return
    }
    this.setTabBarHidden(true)
    this.setData({
      activityVisible: true,
      cartVisible: false,
      specVisible: false
    })
  },
  closeActivitySheet() {
    this.setTabBarHidden(false)
    this.setData({ activityVisible: false })
  },
  openActivityRules() {
    this.closeActivitySheet()
    wx.navigateTo({ url: '/pages/activity-rules/activity-rules' })
  },
  handleActivityPhone() {
    const phone = this.data.currentStore && this.data.currentStore.phone
    if (!phone) {
      wx.showToast({ title: '门店电话暂未配置', icon: 'none' })
      return
    }
    wx.makePhoneCall({ phoneNumber: phone })
  },
  handleActivityUnavailable(event) {
    const label = event.detail.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  },
  handleSpec(event) {
    this.setTabBarHidden(true)
    this.setData({
      specVisible: true,
      specProduct: event.detail.product,
      specMode: 'add',
      specInitialQuantity: 1,
      specInitialSelectedOptionIds: [],
      editingCartItemId: '',
      cartVisible: false
    })
  },
  closeSpec() {
    this.setTabBarHidden(false)
    this.setData({
      specVisible: false,
      specMode: 'add',
      specInitialQuantity: 1,
      specInitialSelectedOptionIds: [],
      editingCartItemId: ''
    })
  },
  handleSpecAddCart(event) {
    const { product, selectedOptions, quantity, unitPrice, originalPrice, specText } = event.detail
    const selectedOptionIds = selectedOptions.map(option => option.id)
    const cartId = buildCartId(product.id, selectedOptionIds)
    const cartItems = this.data.cartItems.map(item => ({ ...item }))
    const existing = cartItems.find(item => item.id === cartId)

    if (existing) {
      existing.quantity += quantity
    } else {
      cartItems.push({
        id: cartId,
        productId: product.id,
        selectedOptionIds,
        name: product.name,
        spec: specText,
        price: unitPrice,
        originalPrice,
        quantity,
        selected: true,
        image: product.image
      })
    }

    const summary = summarizeCart(cartItems)
    this.setTabBarHidden(false)
    this.setData({
      cartItems,
      cartCount: summary.count,
      cartTotal: summary.total,
      specVisible: false
    })
    wx.showToast({ title: '已加入购物车', icon: 'none' })
  },
  handleSpecBuy() {
    wx.showToast({ title: '立即购买暂未接入', icon: 'none' })
  },
  handleSpecFavorite(event) {
    wx.showToast({ title: event.detail.favorite ? '已收藏' : '已取消收藏', icon: 'none' })
  },
  handleCart() {
    if (!this.data.cartItems.length) return
    this.setData({ cartVisible: !this.data.cartVisible })
  },
  closeCart() {
    this.setTabBarHidden(false)
    this.setData({ cartVisible: false })
  },
  handleCartChange(event) {
    const cartItems = event.detail.items || []
    const summary = summarizeCart(cartItems)
    this.setData({
      cartItems,
      cartCount: summary.count,
      cartTotal: summary.total,
      cartVisible: cartItems.length > 0
    })
  },
  handleCartEdit(event) {
    const cartItem = this.data.cartItems.find(item => item.id === event.detail.id)
    if (!cartItem || !cartItem.productId) {
      wx.showToast({ title: '商品规格暂不可编辑', icon: 'none' })
      return
    }
    const product = findProductById(menuTabs, cartItem.productId)
    if (!product) {
      wx.showToast({ title: '商品规格暂不可编辑', icon: 'none' })
      return
    }
    this.setTabBarHidden(true)
    this.setData({
      specProduct: product,
      specMode: 'edit',
      specInitialQuantity: cartItem.quantity,
      specInitialSelectedOptionIds: cartItem.selectedOptionIds || [],
      editingCartItemId: cartItem.id,
      specVisible: true,
      cartVisible: true
    })
  },
  handleCartUpdate(event) {
    this.setTabBarHidden(false)
    const cartItems = mergeEditedCartItem(this.data.cartItems, this.data.editingCartItemId, event.detail)
    const summary = summarizeCart(cartItems)
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
    })
  },
  handleCartInfo() {
    wx.showToast({ title: '优惠说明暂未接入', icon: 'none' })
  },
  handleCheckout() {
    const selectedItems = this.data.cartItems.filter(item => item.selected)
    if (!summarizeCart(this.data.cartItems).count) {
      wx.showToast({ title: '请选择商品', icon: 'none' })
      return
    }
    this.returningFrom = 'order'
    getApp().globalData.pendingOrder = {
      items: selectedItems,
      storeId: this.data.currentStore.id,
      orderMode: this.data.orderMode
    }
    wx.navigateTo({ url: '/pages/order-confirm/order-confirm' })
  },
  setOrderMode(event) {
    const { mode } = event.currentTarget.dataset
    if (!this.data.currentStore || (this.data.currentStore.modes || []).indexOf(mode) === -1) {
      wx.showToast({ title: '当前门店暂不支持该方式', icon: 'none' })
      return
    }
    getApp().globalData.orderMode = mode
    this.setData({ orderMode: mode })
  },
  selectMenuTab(event) {
    const activeMenuIndex = Number(event.currentTarget.dataset.index)
    const activeMenu = this.data.menuTabs[activeMenuIndex]
    const selectedCategoryId = getFirstCategoryId(activeMenu)
    getApp().globalData.menuTabId = activeMenu.id
    this.setData({
      activeMenuIndex,
      activeMenu,
      selectedCategoryId,
      selectedGroupId: activeMenu.groups[0].id,
      scrollIntoView: 'product-top'
    })
  },
  selectCategory(event) {
    const { id } = event.currentTarget.dataset
    this.setData({
      selectedCategoryId: id,
      selectedGroupId: getGroupIdByCategoryId(this.data.activeMenu, id),
      scrollIntoView: `category-${id}`
    })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  }
}))
