const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { formatOrderAmount } = require('../../data/mock');

function toYuan(fen) {
  const value = Number(fen);
  if (!Number.isFinite(value)) return 0;
  return Math.round(value) / 100;
}

Page(withShare({
  data: {
    orderNo: '',
    amountText: '0',
    loading: true,
    loadFailed: false
  },

  onLoad(options) {
    const opts = options || {};
    const orderNo = opts.orderNo || '';
    this.setData({ orderNo });
    if (orderNo) {
      this.refreshOrder(orderNo);
    } else {
      this.setData({ loading: false });
    }
  },

  /** 回读服务端订单：金额以服务端为准，避免依赖本地传参。 */
  refreshOrder(orderNo) {
    api
      .fetchOrderDetail(orderNo)
      .then(order => {
        const amount = order && order.paidAmount != null ? order.paidAmount : null;
        this.setData({
          loading: false,
          amountText: amount == null ? '0' : formatOrderAmount(toYuan(amount))
        });
      })
      .catch(() => {
        // 查单失败不阻断结果页展示，金额回落到 0，订单号仍可复制
        this.setData({ loading: false, loadFailed: true });
      });
  },

  copyOrderNo() {
    const orderNo = this.data.orderNo;
    if (!orderNo) return;
    wx.setClipboardData({ data: orderNo });
  },

  viewOrders() {
    wx.switchTab({ url: '/pages/orders/orders' });
  },

  backHome() {
    wx.switchTab({ url: '/pages/home/home' });
  }
}));

