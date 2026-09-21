const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const { pointsProducts } = require('../../data/mock');
const { getPoints, exchangeProduct } = require('../../utils/points');

function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

Page(
  withShare({
    data: {
      item: null,
      quantity: 1,
      insufficient: true,
      stock: 0,
      maxQuantity: 99
    },
    onLoad(options) {
      const item = pointsProducts.find(product => product.id === options.id) || pointsProducts[0];
      const limit = item.purchaseLimit || 0;
      const stockCap = Math.min(item.stock, 99);
      this.setData({
        item,
        quantity: 1,
        stock: item.stock,
        maxQuantity: limit > 0 ? Math.min(limit, stockCap) : stockCap,
        insufficient: getPoints() < item.points
      });
    },
    refreshInsufficient() {
      const cost = roundMoney(this.data.item.points * this.data.quantity);
      this.setData({ insufficient: getPoints() < cost });
    },
    decreaseQuantity() {
      if (this.data.quantity <= 1) return;
      this.setData({ quantity: this.data.quantity - 1 }, () => this.refreshInsufficient());
    },
    increaseQuantity() {
      if (this.data.quantity >= this.data.maxQuantity) return;
      this.setData({ quantity: this.data.quantity + 1 }, () => this.refreshInsufficient());
    },
    handleExchange() {
      loginGuard.requirePhone(() => this.doHandleExchange(), { reason: '兑换需要绑定手机号' });
    },

    doHandleExchange() {
      if (this.data.insufficient) {
        wx.showToast({ title: '时光币不足', icon: 'none' });
        return;
      }
      const item = this.data.item;
      const quantity = this.data.quantity;
      const cost = roundMoney(item.points * quantity);
      wx.showModal({
        title: '确认兑换',
        content: `将消耗 ${cost} 时光币兑换「${item.name}」x${quantity}`,
        confirmText: '确认兑换',
        cancelText: '再想想',
        success: res => {
          if (!res.confirm) return;
          this.doExchange();
        }
      });
    },
    doExchange() {
      const item = this.data.item;
      const quantity = this.data.quantity;
      const limit = item.purchaseLimit || 0;
      if (limit > 0 && quantity > limit) {
        wx.showToast({ title: `该商品每人限购 ${limit} 件`, icon: 'none' });
        return;
      }
      const app = getApp();
      const result = exchangeProduct({
        product: item,
        quantity,
        currentPoints: getPoints(),
        pointsRecords: app.globalData.pointsRecords || [],
        exchangeRecords: app.globalData.exchangeRecords || []
      });
      if (!result.ok) {
        wx.showToast({ title: '时光币不足，无法兑换', icon: 'none' });
        this.refreshInsufficient();
        return;
      }
      this.setData({
        stock: Math.max(0, this.data.stock - quantity),
        insufficient: true
      });
      wx.showToast({ title: '兑换成功', icon: 'success' });
      const isCoupon = item.category === 'coupon';
      const targetUrl = isCoupon
        ? '/pages/coupon-list/coupon-list'
        : '/pages/gift-card-orders/gift-card-orders?status=pending_verify';
      wx.redirectTo({ url: targetUrl });
    }
  })
);


