const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getPoints } = require('../../utils/points');

function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

/** 积分商品对外 id 使用后端 code（与 mock 时代的 id 一致）。 */
function normalizeId(product) {
  return String((product && (product.code || product.id)) || "");
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
      // 兑换商品来自后端积分商品列表（按 code 匹配）
      api
        .fetchPointsProducts()
        .then(list => (Array.isArray(list) ? list : []))
        .catch(() => [])
        .then(list => {
          const productId = options.id || '';
          const item = list.find(product => normalizeId(product) === productId) || list[0] || {};
          const limit = Number(item.purchaseLimit) || 0;
          const stock = Number(item.stock) || 0;
          const points = Number(item.points) || 0;
          const stockCap = Math.min(stock, 99);
          this.setData({
            item: Object.assign({}, item, { points }),
            quantity: 1,
            stock,
            maxQuantity: limit > 0 ? Math.min(limit, stockCap) : stockCap,
            insufficient: getPoints() < points
          });
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
      // 兑换为服务端写操作：扣减时光币、生成兑换单均以后端为准
      const isCoupon = item.category === 'coupon';
      api
        .exchangePointsProduct(item.id)
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


