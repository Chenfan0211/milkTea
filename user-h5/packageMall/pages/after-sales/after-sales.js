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

function decorate(record) {
  const status = mall.normalizeStatus(record && record.status);
  const items = (record && Array.isArray(record.items) ? record.items : []).map(item => ({
    name: String(item.name || '商城商品'),
    spec: String(item.spec || ''),
    image: mall.imageUrl(item.image),
    quantity: Number(item.quantity) || 0,
    unitPriceText: mall.priceText(Number(item.unitPrice) || 0)
  }));
  return Object.assign({}, record, {
    status,
    statusText: STATUS_TEXT[status] || status || '未知状态',
    refundAmountText: mall.moneyText(record && record.refundAmount),
    applyTimeText: record && record.applyTime ? record.applyTime : '—',
    reviewTimeText: record && record.reviewTime ? record.reviewTime : '—',
    items
  });
}

Page({
  data: {
    orderNo: '',
    records: [],
    loading: true,
    needLogin: false,
    error: ''
  },

  onLoad(options) {
    this.setData({ orderNo: String((options && options.orderNo) || '').trim() });
    this.loadAfterSales();
  },

  onShow() {
    if (auth.isLoggedIn()) this.loadAfterSales();
  },

  loadAfterSales() {
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, needLogin: true, error: '' });
      return;
    }
    this.setData({ loading: !this.data.records.length, needLogin: false, error: '' });
    const task = this.data.orderNo
      ? api.fetchOrderAfterSales(this.data.orderNo)
      : api.fetchAfterSales(1, 50).then(data => (data && data.records) || []);
    task
      .then(data => {
        this.setData({
          records: (Array.isArray(data) ? data : []).map(decorate),
          loading: false,
          error: ''
        });
      })
      .catch(error => {
        this.setData({ loading: false, error: (error && error.message) || '售后记录加载失败' });
      });
  },

  openDetail(event) {
    const no = String(event.currentTarget.dataset.no || '').trim();
    if (!no) return;
    wx.navigateTo({
      url: `/packageMall/pages/after-sale-detail/after-sale-detail?afterSaleNo=${encodeURIComponent(no)}`
    });
  },

  retry() {
    this.loadAfterSales();
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadAfterSales(), { reason: '登录后可查看售后记录' });
  }
});
