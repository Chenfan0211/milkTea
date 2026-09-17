const { withShare } = require('../../utils/share')
const { getFavoriteStoreIds, resolveStoreCatalog, selectStore: persistSelectedStore, toggleFavoriteStore } = require('../../utils/store')

Page(withShare({
  data: {
    currentCityName: '',
    favoriteStores: []
  },
  onLoad() {
    this.loadFavorites()
  },
  onShow() {
    this.loadFavorites()
  },
  loadFavorites() {
    const catalog = resolveStoreCatalog()
    const favoriteStoreIds = getFavoriteStoreIds()
    const favoriteStores = catalog.stores.filter(store => favoriteStoreIds.indexOf(store.id) !== -1)
    this.setData({
      currentCityName: catalog.city.name,
      favoriteStores
    })
  },
  handleSelectFavoriteStore(event) {
    const { id } = event.currentTarget.dataset
    const store = this.data.favoriteStores.find(item => item.id === id)
    if (!store) return
    persistSelectedStore(store.id)
    const app = getApp()
    app.globalData.selectedStoreId = store.id
    app.globalData.selectedCityCode = store.cityCode
    app.globalData.selectedCityName = this.data.currentCityName
    app.globalData.favoriteStoreSelected = true
    wx.navigateBack()
  },
  handleRemoveFavorite(event) {
    const { id } = event.currentTarget.dataset
    const result = toggleFavoriteStore(id)
    this.loadFavorites()
    wx.showToast({ title: result.favorite ? '已收藏' : '已取消收藏', icon: 'none' })
  }
}))
