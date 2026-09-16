const { orderCategories, orders, formatOrderAmount } = require('../../data/mock')

const decoratedOrders = orders.map(order => Object.assign({}, order, {
  amountText: formatOrderAmount(order.amount),
  firstItem: order.items[0]
}))

Page({
  data: {
    orderCategories,
    orders: decoratedOrders,
    activeCategory: 'all',
    filteredOrders: decoratedOrders
  },
  onShow() {
    if (this.getTabBar) this.getTabBar().setData({ selected: 3 })
  },
  selectCategory(event) {
    const { id } = event.currentTarget.dataset
    const filteredOrders = id === 'all'
      ? this.data.orders
      : this.data.orders.filter(order => order.category === id)
    this.setData({ activeCategory: id, filteredOrders })
  },
  showInvoice() {
    wx.showToast({ title: '开发票暂未接入', icon: 'none' })
  },
  openOrderDetail(event) {
    const { id } = event.currentTarget.dataset
    wx.navigateTo({ url: `/pages/order-detail/order-detail?id=${id}` })
  },
  showUnavailable() {
    wx.showToast({ title: '功能暂未接入', icon: 'none' })
  }
})