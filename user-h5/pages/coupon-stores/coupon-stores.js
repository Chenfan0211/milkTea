const { withShare } = require('../../utils/share')
const { coupons } = require('../../data/mock')
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store')

function resolveStores(couponId, activeStores) {
  const coupon = coupons.find(item => item.id === couponId)
  const applicableStoreIds = coupon && Array.isArray(coupon.applicableStoreIds)
    ? coupon.applicableStoreIds
    : []

  if (!applicableStoreIds.length) return activeStores.map(store => Object.assign({}, store))

  const applicableStores = activeStores.filter(store => applicableStoreIds.indexOf(store.id) !== -1)
  return (applicableStores.length ? applicableStores : activeStores).map(store => Object.assign({}, store))
}

Page(withShare({
  data: {
    couponId: '',
    nextPage: '',
    currentCity: '长沙市',
    currentAddress: '',
    stores: []
  },
  onLoad(options) {
    const catalog = resolveStoreCatalog()
    const activeStores = catalog.stores
    const applicableStores = resolveStores(options.couponId, activeStores)
    const firstStore = applicableStores[0] || activeStores[0]
    this.setData({
      couponId: options.couponId || '',
      nextPage: options.next === 'products' ? 'products' : '',
      currentCity: catalog.city.name,
      stores: applicableStores,
      currentAddress: firstStore ? firstStore.address : ''
    })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  },
  handleSelectStore(event) {
    const { id } = event.currentTarget.dataset
    if (!id) return
    if (this.data.nextPage === 'products') {
      wx.navigateTo({ url: `/pages/coupon-products/coupon-products?couponId=${this.data.couponId}&storeId=${id}` })
      return
    }
    persistSelectedStore(id)
    getApp().globalData.selectedStoreId = id
    wx.navigateBack()
  },
  handleNavigate(event) {
    const { id } = event.currentTarget.dataset
    const store = this.data.stores.find(item => item.id === id)
    if (!store) return
    wx.openLocation({ latitude: store.latitude, longitude: store.longitude, name: store.name, address: store.address, scale: 16 })
  }
}))
