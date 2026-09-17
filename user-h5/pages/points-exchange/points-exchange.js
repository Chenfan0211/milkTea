const { withShare } = require('../../utils/share')
const { pointsProducts } = require('../../data/mock')

Page(withShare({
  data: {
    item: null,
    quantity: 1,
    insufficient: true
  },
  onLoad(options) {
    const item = pointsProducts.find(product => product.id === options.id) || pointsProducts[0]
    const app = getApp()
    this.setData({
      item,
      quantity: 1,
      insufficient: app.globalData.points < item.points
    })
  },
  decreaseQuantity() {
    if (this.data.quantity <= 1) return
    this.setData({ quantity: this.data.quantity - 1 })
  },
  increaseQuantity() {
    const maxQuantity = Math.min(this.data.item.stock, 99)
    if (this.data.quantity >= maxQuantity) return
    this.setData({ quantity: this.data.quantity + 1 })
  },
  handleExchange() {
    if (this.data.insufficient) return
    wx.showToast({ title: '兑换功能暂未接入', icon: 'none' })
  }
}))
