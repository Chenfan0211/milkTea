const { withShare } = require('../../utils/share')
const { profileFunctions } = require('../../data/mock')
const { getUserProfile } = require('../../utils/user-profile')

const initialProfile = getUserProfile()

Page(withShare({
  data: {
    profileFunctions,
    userProfile: initialProfile,
    stats: [
      { id: 'coupon', label: '优惠券', value: initialProfile.couponCount },
      { id: 'balance', label: '余额', value: 0 },
      { id: 'points', label: '积分', value: initialProfile.points },
      { id: 'gift', label: '礼品卡', value: 0 }
    ],
    progressPercent: 0
  },
  onLoad() {
    const { progressCurrent, progressTarget } = this.data.userProfile
    this.setData({ progressPercent: Math.min(100, Math.round(progressCurrent / progressTarget * 100)) })
  },
  onShow() {
    const points = getApp().globalData.points
    const stats = this.data.stats.map(item => (
      item.id === 'points' ? Object.assign({}, item, { value: points }) : item
    ))
    this.setData({ stats, userProfile: getUserProfile() })
    if (this.getTabBar) this.getTabBar().setData({ selected: 4 })
  },
  openProfileData() {
    wx.navigateTo({ url: '/pages/profile-data/profile-data' })
  },
  openGiftCards() {
    wx.navigateTo({ url: '/pages/gift-card/gift-card' })
  },
  openMenu() {
    wx.switchTab({ url: '/pages/menu/menu' })
  },
  handleProfileAction(event) {
    const { id, label } = event.currentTarget.dataset
    if (id === 'balance') {
      wx.navigateTo({ url: '/pages/stored-value/stored-value' })
      return
    }
    if (id === 'coupon' || id === 'coupon-wallet') {
      wx.navigateTo({ url: '/pages/coupon-list/coupon-list' })
      return
    }
    if (id === 'points') {
      wx.navigateTo({ url: '/pages/points-mall/points-mall' })
      return
    }
    wx.showToast({ title: `${label || '功能'}暂未接入`, icon: 'none' })
  },
  showUnavailable(event) {
    const label = event.currentTarget.dataset.label || '功能'
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  }
}))
