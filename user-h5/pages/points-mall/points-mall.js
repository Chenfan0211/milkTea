const { pointsCategories, pointsProducts, pointsSignIn, stores } = require('../../data/mock')

Page({
  data: {
    pointsBalance: 0,
    signedToday: false,
    pointsCategories,
    pointsProducts,
    filteredProducts: pointsProducts,
    activeCategory: 'all',
    currentStore: stores[0]
  },
  onLoad() {
    const app = getApp()
    const currentStore = stores.find(store => store.id === app.globalData.selectedStoreId) || stores[0]
    this.setData({ currentStore })
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
    wx.showActionSheet({
      itemList: stores.map(store => store.name),
      success: ({ tapIndex }) => {
        const currentStore = stores[tapIndex]
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
  handleSearch() {
    wx.showToast({ title: '搜索功能暂未接入', icon: 'none' })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  }
})
