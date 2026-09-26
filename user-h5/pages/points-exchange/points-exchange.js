const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getPoints } = require('../../utils/points');

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
      maxQuantity: 99,
      isCoupon: false
    },
    onLoad(options) {
      return api
        .fetchPointsProducts()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(list => {
          const productId = String((options && options.id) || '');
          const item = list.find(product => String(product.id) === productId);
          if (!item) {
            this.setData({
              item: null,
              quantity: 1,
              insufficient: true,
              stock: 0,
              maxQuantity: 1,
              isCoupon: false
            });
            return;
          }
          const isCoupon = item.category === 'coupon';
          const limit = Number(item.purchaseLimit) || 0;
          const stock = Number(item.stock) || 0;
          const points = Number(item.points) || 0;
          const stockCap = Math.min(stock, 99);
          this.setData({
            item: Object.assign({}, item, { points }),
            quantity: 1,
            stock,
            maxQuantity: isCoupon ? 1 : (limit > 0 ? Math.min(limit, stockCap) : stockCap),
            insufficient: getPoints() < points,
            isCoupon
          });
        });
    },
    refreshInsufficient() {
      if (!this.data.item) {
        this.setData({ insufficient: true });
        return;
      }
      const cost = roundMoney(this.data.item.points * this.data.quantity);
      this.setData({ insufficient: getPoints() < cost });
    },
    decreaseQuantity() {
      if (this.data.isCoupon || this.data.quantity <= 1) return;
      this.setData({ quantity: this.data.quantity - 1 }, () => this.refreshInsufficient());
    },
    increaseQuantity() {
      if (this.data.isCoupon) return;
      if (this.data.quantity >= this.data.maxQuantity) return;
      this.setData({ quantity: this.data.quantity + 1 }, () => this.refreshInsufficient());
    },
    handleExchange() {
      loginGuard.requirePhone(() => this.doHandleExchange(), { reason: '兑换需要绑定手机号' });
    },

    doHandleExchange() {
      if (!this.data.item) {
        wx.showToast({ title: '商品不存在或已下架', icon: 'none' });
        return;
      }
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
      if (!item) {
        wx.showToast({ title: '商品不存在或已下架', icon: 'none' });
        return Promise.resolve();
      }
      const isCoupon = this.data.isCoupon || item.category === 'coupon';
      const quantity = isCoupon ? 1 : this.data.quantity;
      const limit = item.purchaseLimit || 0;
      if (limit > 0 && quantity > limit) {
        wx.showToast({ title: `该商品每人限购 ${limit} 件`, icon: 'none' });
        return Promise.resolve();
      }
      // 兑换为服务端写操作：扣减时光币、生成兑换单均以后端为准
      return api
        .exchangePointsProduct(item.id, quantity)
        .then(() => {
          this.setData({
            stock: Math.max(0, this.data.stock - quantity),
            insufficient: true
          });
          wx.showToast({ title: '兑换成功', icon: 'success' });
          const targetUrl = isCoupon
            ? '/pages/coupon-list/coupon-list'
            : '/pages/gift-card-orders/gift-card-orders?status=pending_verify';
          wx.redirectTo({ url: targetUrl });
        })
        .catch(error => {
          wx.showToast({ title: (error && error.message) || '兑换失败，请稍后重试', icon: 'none' });
          this.refreshInsufficient();
        });
    }
  })
);

