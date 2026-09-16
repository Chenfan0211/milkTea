const { homeShortcuts, userProfile } = require('../../data/mock')

Page({
  data: {
    homeShortcuts,
    userProfile
  },
  onShow() {
    if (this.getTabBar) this.getTabBar().setData({ selected: 0 })
  },
  openCoupons() {
    wx.navigateTo({ url: '/pages/coupon-list/coupon-list' })
  },
  selectOrderMode(event) {
    const { mode } = event.currentTarget.dataset
    getApp().globalData.orderMode = mode
    wx.switchTab({ url: '/pages/menu/menu' })
  },
  handleShortcut(event) {
    const { id, label } = event.currentTarget.dataset
    if (id === 'points-mall') {
      wx.navigateTo({ url: '/pages/points-mall/points-mall' })
      return
    }
    this.showUnavailable({ currentTarget: { dataset: { label } } })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  }
})
