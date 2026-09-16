const { coupons, stores } = require('../../data/mock')

function resolveStores(couponId) {
  const coupon = coupons.find(item => item.id === couponId)
  const applicableStoreIds = coupon && Array.isArray(coupon.applicableStoreIds)
    ? coupon.applicableStoreIds
    : []

  if (!applicableStoreIds.length) return stores.map(store => Object.assign({}, store))

  const applicableStores = stores.filter(store => applicableStoreIds.indexOf(store.id) !== -1)
  return (applicableStores.length ? applicableStores : stores).map(store => Object.assign({}, store))
}

Page({
  data: {
    currentCity: '长沙市',
    currentAddress: '',
    stores: []
  },
  onLoad(options) {
    const applicableStores = resolveStores(options.couponId)
    const firstStore = applicableStores[0] || stores[0]
    this.setData({
      stores: applicableStores,
      currentAddress: firstStore ? firstStore.address : ''
    })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  },
  handleNavigate() {
    wx.showToast({ title: '导航功能暂未接入', icon: 'none' })
  }
})
