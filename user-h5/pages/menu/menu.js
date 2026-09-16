const { initialCartItems, menuTabs, stores } = require('../../data/mock')

function getFirstCategoryId(menu) {
  return menu.groups[0].categories[0].id
}

function getGroupIdByCategoryId(menu, categoryId) {
  const group = menu.groups.find(item => item.categories.some(category => category.id === categoryId))
  return group ? group.id : menu.groups[0].id
}

function summarizeCart(items) {
  const selectedItems = items.filter(item => item.selected)
  return {
    count: selectedItems.reduce((sum, item) => sum + item.quantity, 0),
    total: Math.round(selectedItems.reduce((sum, item) => sum + item.price * item.quantity, 0) * 10) / 10
  }
}

Page({
  data: {
    stores,
    storeNames: stores.map(store => store.name),
    currentStore: stores[0],
    orderMode: 'pickup',
    menuTabs,
    activeMenuIndex: 0,
    activeMenu: menuTabs[0],
    selectedCategoryId: getFirstCategoryId(menuTabs[0]),
    selectedGroupId: menuTabs[0].groups[0].id,
    scrollIntoView: '',
    cartVisible: false,
    specVisible: false,
    specProduct: {},
    cartItems: initialCartItems,
    cartCount: summarizeCart(initialCartItems).count,
    cartTotal: summarizeCart(initialCartItems).total
  },
  onLoad() {
    const app = getApp()
    const currentStore = stores.find(store => store.id === app.globalData.selectedStoreId) || stores[0]
    const activeMenuIndex = Math.max(0, menuTabs.findIndex(item => item.id === app.globalData.menuTabId))
    const activeMenu = menuTabs[activeMenuIndex]
    this.setData({
      currentStore,
      orderMode: app.globalData.orderMode,
      activeMenuIndex,
      activeMenu,
      selectedCategoryId: getFirstCategoryId(activeMenu),
      selectedGroupId: activeMenu.groups[0].id,
      scrollIntoView: ''
    })
  },
  onShow() {
    if (this.getTabBar) this.getTabBar().setData({ selected: 1, hidden: false })
    const app = getApp()
    const updates = {}
    const orderMode = app.globalData.orderMode
    if (orderMode && orderMode !== this.data.orderMode) updates.orderMode = orderMode
    const currentStore = stores.find(store => store.id === app.globalData.selectedStoreId)
    if (currentStore && currentStore.id !== this.data.currentStore.id) updates.currentStore = currentStore
    if (Object.keys(updates).length) this.setData(updates)
  },
  selectStore() {
    wx.showActionSheet({
      itemList: this.data.storeNames,
      success: ({ tapIndex }) => {
        const currentStore = this.data.stores[tapIndex]
        getApp().globalData.selectedStoreId = currentStore.id
        this.setData({ currentStore })
      }
    })
  },
  handleSpec(event) {
    if (this.getTabBar) this.getTabBar().setData({ hidden: true })
    this.setData({
      specVisible: true,
      specProduct: event.detail.product,
      cartVisible: false
    })
  },
  closeSpec() {
    if (this.getTabBar) this.getTabBar().setData({ hidden: false })
    this.setData({ specVisible: false })
  },
  handleSpecAddCart(event) {
    const { product, selectedOptions, quantity, unitPrice, originalPrice, specText } = event.detail
    const cartId = `${product.id}-${selectedOptions.map(option => option.id).join('-')}`
    const cartItems = this.data.cartItems.map(item => ({ ...item }))
    const existing = cartItems.find(item => item.id === cartId)

    if (existing) {
      existing.quantity += quantity
    } else {
      cartItems.push({
        id: cartId,
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
    if (this.getTabBar) this.getTabBar().setData({ hidden: false })
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
  handleCartEdit() {
    wx.showToast({ title: '规格编辑暂未接入', icon: 'none' })
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
    getApp().globalData.pendingOrder = {
      items: selectedItems,
      storeId: this.data.currentStore.id,
      orderMode: this.data.orderMode
    }
    wx.navigateTo({ url: '/pages/order-confirm/order-confirm' })
  },
  setOrderMode(event) {
    const { mode } = event.currentTarget.dataset
    if (this.data.currentStore.modes.indexOf(mode) === -1) {
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
})
