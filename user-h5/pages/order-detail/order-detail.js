const { formatOrderAmount, orders } = require('../../data/mock')

function decorateOrder(order) {
  const orderStatus = order.orderStatus || (order.status === '已完成' ? 'completed' : 'paid_pickup')
  return {
    ...order,
    orderStatus,
    amountText: formatOrderAmount(order.amount),
    originalAmountText: formatOrderAmount(order.originalAmount || order.amount),
    discountAmountText: formatOrderAmount(Math.abs(order.discountAmount || 0)),
    couponAmountText: formatOrderAmount(Math.abs(order.couponAmount || 0))
  }
}

Page({
  data: {
    order: {}
  },
  onLoad(options) {
    const order = orders.find(item => item.id === options.id)
    if (!order) {
      wx.showToast({ title: '订单不存在', icon: 'none' })
      setTimeout(() => wx.navigateBack(), 500)
      return
    }
    this.setData({ order: decorateOrder(order) })
  },

  handleCoupon() {
    wx.showToast({ title: '领券功能暂未接入', icon: 'none' })
  },
  handleReorder() {
    wx.showToast({ title: '再次购买暂未接入', icon: 'none' })
  },
  handleReview() {
    wx.showToast({ title: '评价功能暂未接入', icon: 'none' })
  },
  handleCopy() {
    const orderNo = this.data.order.orderInfo && this.data.order.orderInfo.orderNo
    if (!orderNo) return
    wx.setClipboardData({ data: orderNo })
  }
})