const { withShare } = require('../../utils/share')
const { storedValuePackages, userProfile } = require('../../data/mock')
const {
  MAX_STORED_VALUE_QUANTITY,
  buildStoredValueSummary,
  changeStoredValueQuantity
} = require('../../utils/stored-value')
const { resolveStoreCatalog } = require('../../utils/store')

function getDisplayStore() {
  const catalog = resolveStoreCatalog()
  const store = catalog.currentStore || catalog.stores[0] || {}
  const distanceValue = Number(store.distanceValue)
  return Object.assign({}, store, {
    displayDistance: Number.isFinite(distanceValue) ? `距您${distanceValue.toFixed(1)}km` : (store.distanceText || '')
  })
}

Page(withShare({
  data: {
    balanceText: '0',
    currentStore: {},
    storedValuePackage: storedValuePackages[0] || {},
    quantity: 1,
    maxQuantity: MAX_STORED_VALUE_QUANTITY,
    totalText: '0',
    giftItems: [],
    usageParagraphs: []
  },
  onLoad() {
    this.setData({ balanceText: String(userProfile.balance || 0) })
    this.syncStore()
    this.updateSummary(this.data.quantity)
  },
  onShow() {
    this.syncStore()
  },
  syncStore() {
    this.setData({ currentStore: getDisplayStore() })
  },
  updateSummary(quantity) {
    const summary = buildStoredValueSummary(this.data.storedValuePackage, quantity)
    this.setData({
      quantity: summary.quantity,
      totalText: summary.totalText,
      giftItems: summary.giftItems,
      usageParagraphs: summary.usageParagraphs
    })
  },
  changeQuantity(event) {
    const delta = Number(event.currentTarget.dataset.delta || 0)
    const nextQuantity = changeStoredValueQuantity(this.data.quantity, delta)
    if (nextQuantity === this.data.quantity) return
    this.updateSummary(nextQuantity)
  },
  selectStore() {
    wx.switchTab({ url: '/pages/menu/menu' })
  },
  handleRecord() {
    this.showUnavailable('余额记录')
  },
  handleManage() {
    this.showUnavailable('余额管理')
  },
  handleRecharge() {
    this.showUnavailable('储值支付')
  },
  showUnavailable(label) {
    wx.showToast({ title: `${label}暂未接入`, icon: 'none' })
  }
}))