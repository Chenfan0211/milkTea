const { withShare } = require('../../utils/share')
const { formatOrderAmount } = require('../../data/mock')
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store')

function roundMoney(value) {
  return Math.round(value * 10) / 10
}

function summarize(items) {
  const count = items.reduce((sum, item) => sum + item.quantity, 0)
  const amount = roundMoney(items.reduce((sum, item) => sum + item.price * item.quantity, 0))
  const original = roundMoney(items.reduce((sum, item) => sum + (item.originalPrice || item.price) * item.quantity, 0))
  return {
    count,
    amount,
    discount: roundMoney(original - amount)
  }
}

Page(withShare({
  data: {
    store: {},
    items: [],
    orderMode: 'pickup',
    modeOptions: [],
    count: 0,
    amountText: '0',
    discountText: '0',
    pointCount: 0,
    phone: '',
    remark: ''
  },
  onLoad() {
    const app = getApp()
    const pending = app.globalData.pendingOrder
    if (!pending || !pending.items || !pending.items.length) {
      wx.showToast({ title: '暂无结算商品', icon: 'none' })
      setTimeout(() => wx.navigateBack(), 800)
      return
    }
    const catalog = resolveStoreCatalog()
    const store = catalog.stores.find(item => item.id === pending.storeId) || catalog.currentStore || catalog.stores[0]
    const orderMode = pending.orderMode || app.globalData.orderMode || 'pickup'
    this.applyOrder(pending.items, store, orderMode)
  },
  applyOrder(items, store, orderMode) {
    const summary = summarize(items)
    this.setData({
      items,
      store,
      orderMode,
      modeOptions: [
        {
          value: 'dinein',
          label: '店内就餐',
          icon: '/assets/icons/lucide/dine-in.svg',
          disabled: store.modes.indexOf('dinein') === -1
        },
        {
          value: 'pickup',
          label: '打包外带',
          icon: '/assets/icons/lucide/takeaway.svg',
          disabled: store.modes.indexOf('pickup') === -1
        }
      ],
      count: summary.count,
      amountText: formatOrderAmount(summary.amount),
      discountText: formatOrderAmount(summary.discount),
      pointCount: Math.floor(summary.amount / 10)
    })
  },
  selectMode(event) {
    const { mode } = event.currentTarget.dataset
    const option = this.data.modeOptions.find(item => item.value === mode)
    if (!option || option.disabled) {
      wx.showToast({ title: '当前门店暂不支持该方式', icon: 'none' })
      return
    }
    getApp().globalData.orderMode = mode
    this.setData({ orderMode: mode })
  },
  selectStore() {
    const catalog = resolveStoreCatalog()
    const availableStores = catalog.stores
    wx.showActionSheet({
      itemList: availableStores.map(store => store.name),
      success: ({ tapIndex }) => {
        const store = availableStores[tapIndex]
        persistSelectedStore(store.id)
        const app = getApp()
        app.globalData.selectedStoreId = store.id
        let orderMode = this.data.orderMode
        if (store.modes.indexOf(orderMode) === -1) orderMode = store.modes[0]
        app.globalData.orderMode = orderMode
        this.applyOrder(this.data.items, store, orderMode)
      }
    })
  },
  handlePhoneInput(event) {
    this.setData({ phone: event.detail.value })
  },
  handleRemarkInput(event) {
    this.setData({ remark: event.detail.value })
  },
  handleCoupon() {
    wx.showToast({ title: '优惠券暂未接入', icon: 'none' })
  },

  handleSubmit() {
    wx.showToast({ title: '支付暂未接入', icon: 'none' })
  }
}))
