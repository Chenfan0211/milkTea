const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mall = require('../../utils/mall');

const STATUS_TEXT = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  REFUNDING: '退款中',
  SUCCESS: '退款成功',
  FAILED: '退款失败'
};

Page({
  data: {
    afterSaleNo: '',
    record: null,
    loading: true,
    needLogin: false,
    error: ''
  },

  onLoad(options) {
    const afterSaleNo = String((options && options.afterSaleNo) || '').trim();
    this.setData({ afterSaleNo });
    if (!afterSaleNo) {
      this.setData({ loading: false, error: '售后单号缺失' });
      return;
    }
    this.loadAfterSale();
  },

  onShow() {
    if (this.data.afterSaleNo && auth.isLoggedIn() && this.data.record) this.loadAfterSale();
  },

  loadAfterSale() {
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, needLogin: true, error: '' });
      return;
    }
    this.setData({ loading: !this.data.record, needLogin: false, error: '' });
    api
      .fetchAfterSaleDetail(this.data.afterSaleNo)
      .then(record => {
        const status = mall.normalizeStatus(record && record.status);
        const items = (record && Array.isArray(record.items) ? record.items : []).map(item => ({
          orderItemId: item.orderItemId,
          name: String(item.name || '商城商品'),
          spec: String(item.spec || ''),
          image: mall.imageUrl(item.image),
          quantity: Number(item.quantity) || 0,
          unitPriceText: mall.priceText(Number(item.unitPrice) || 0),
          lineAmountText: mall.moneyText(item.lineAmount)
        }));
        this.setData({
          record: Object.assign({}, record, {
            status,
            statusText: STATUS_TEXT[status] || status || '未知状态',
            refundAmountText: mall.moneyText(record && record.refundAmount),
            applyTimeText: record && record.applyTime ? record.applyTime : '—',
            reviewTimeText: record && record.reviewTime ? record.reviewTime : '—',
            completeTimeText: record && record.completeTime ? record.completeTime : '—',
            refundFailReason: record && record.refundFailReason ? record.refundFailReason : '',
            items
          }),
          loading: false,
          error: ''
        });
      })
      .catch(error => {
        this.setData({ loading: false, error: (error && error.message) || '售后详情加载失败' });
      });
  },

  copyAfterSaleNo() {
    const no = this.data.afterSaleNo;
    if (!no) return;
    wx.setClipboardData({ data: no, success: () => wx.showToast({ title: '单号已复制', icon: 'none' }) });
  },

  openOrder() {
    const orderNo = this.data.record && this.data.record.orderNo;
    if (!orderNo) return;
    wx.navigateTo({ url: `/packageMall/pages/order-detail/order-detail?orderNo=${encodeURIComponent(orderNo)}` });
  },

  retry() {
    this.loadAfterSale();
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadAfterSale(), { reason: '登录后可查看售后详情' });
  }
});
