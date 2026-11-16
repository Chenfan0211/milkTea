const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mall = require('../../utils/mall');
const PAGE_SIZE = 20;

const STATUS_TEXT = {
  PENDING: '待填写退货物流',
  RETURN_SHIPPED: '退货已寄出',
  RETURN_RECEIVED: '商家已收货',
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
    loadingMore: false,
    page: 1,
    hasMore: true,
    moreError: '',
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

  onUnload() {
    this.afterSalesRequestId = (this.afterSalesRequestId || 0) + 1;
  },

  loadAfterSales(reset = true) {
    if (!reset && (this.data.loading || this.data.loadingMore || !this.data.hasMore)) return;
    if (reset) this.afterSalesRequestId = (this.afterSalesRequestId || 0) + 1;
    const requestId = (this.afterSalesRequestId = this.afterSalesRequestId || 0);
    const userId = auth.getUserId();
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, loadingMore: false, records: [], needLogin: true, error: '' });
      return;
    }
    const page = reset ? 1 : this.data.page + 1;
    this.setData({
      loading: reset,
      loadingMore: !reset,
      page: reset ? 1 : this.data.page,
      needLogin: false,
      error: '',
      moreError: ''
    });
    const task = this.data.orderNo
      ? api.fetchOrderAfterSales(this.data.orderNo).then(data => {
          const records = Array.isArray(data) ? data : [];
          return { records, total: records.length };
        })
      : api.fetchAfterSales(page, PAGE_SIZE);
    return task
      .then(data => {
        if (requestId !== this.afterSalesRequestId || userId !== auth.getUserId()) return;
        const rows = (data && data.records) || [];
        const records = (reset ? [] : this.data.records).concat(rows.map(decorate));
        const hasMore =
          !this.data.orderNo &&
          rows.length > 0 &&
          (data.total == null ? rows.length === PAGE_SIZE : records.length < Number(data.total));
        this.setData({
          records,
          page,
          hasMore,
          loading: false,
          loadingMore: false,
          error: ''
        });
      })
      .catch(error => {
        if (requestId !== this.afterSalesRequestId || userId !== auth.getUserId()) return;
        const message = (error && error.message) || '售后记录加载失败';
        this.setData({
          loading: false,
          loadingMore: false,
          error: reset ? message : '',
          moreError: reset ? '' : message
        });
      });
  },

  loadMore() {
    return this.loadAfterSales(false);
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
