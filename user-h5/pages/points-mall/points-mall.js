const { withShare } = require('../../utils/share')
const { pointsCategories, pointsProducts, pointsSignIn } = require('../../data/mock')
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store')

Page(withShare({
  data: {
    pointsBalance: 0,
    signedToday: false,
    pointsCategories,
    pointsProducts,
    filteredProducts: pointsProducts,
    activeCategory: 'all',
    currentStore: {}
  },
  onLoad() {
    const catalog = resolveStoreCatalog()
    this.setData({ currentStore: catalog.currentStore || catalog.stores[0] || {} })
  },
  onShow() {
    const app = getApp()
    this.setData({
      pointsBalance: app.globalData.points,
      signedToday: app.globalData.signedDates.includes(pointsSignIn.today)
    })
  },
  filterCategory(event) {
    const { id } = event.currentTarget.dataset
    const filteredProducts = id === 'all'
      ? pointsProducts
      : pointsProducts.filter(item => item.category === id)
    this.setData({ activeCategory: id, filteredProducts })
  },
  openProduct(event) {
    wx.navigateTo({ url: `/pages/points-exchange/points-exchange?id=${event.currentTarget.dataset.id}` })
  },
  openPointsDetail() {
    wx.navigateTo({ url: '/pages/points-detail/points-detail' })
  },
  openExchangeRecords() {
    wx.navigateTo({ url: '/pages/exchange-records/exchange-records' })
  },
  selectStore() {
    const catalog = resolveStoreCatalog()
    const availableStores = catalog.stores
    wx.showActionSheet({
      itemList: availableStores.map(store => store.name),
      success: ({ tapIndex }) => {
        const currentStore = availableStores[tapIndex]
        persistSelectedStore(currentStore.id)
        getApp().globalData.selectedStoreId = currentStore.id
        this.setData({ currentStore })
      }
    })
  },
  handleCheckIn() {
    wx.navigateTo({ url: '/pages/points-signin/points-signin' })
  },
  openSignInRules() {
    wx.navigateTo({ url: '/pages/points-signin-rules/points-signin-rules' })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  }
}))
