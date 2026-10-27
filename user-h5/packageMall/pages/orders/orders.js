const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const { requestOrderPayment } = require('../../../utils/order-payment');
const mall = require('../../utils/mall');

const PAGE_SIZE = 20;
const STATUS_TABS = [
  { value: 'all', label: '全部' },
  { value: 'CREATED', label: '待支付' },
  { value: 'PAID', label: '待发货' },
  { value: 'SHIPPED', label: '待收货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELED', label: '已取消' },
  { value: 'REFUNDED', label: '已退款' }
];

Page({
  data: {
    statusTabs: STATUS_TABS,
    activeStatus: 'all',
    orders: [],
    page: 1,
    hasMore: true,
    loading: true,
    loadingMore: false,
    needLogin: false,
    error: '',
    actionOrderNo: ''
  },

  onLoad(options) {
    const status = String((options && options.status) || 'all');
    this.setData({
      activeStatus: STATUS_TABS.some(tab => tab.value === status) ? status : 'all'
    });
  },

  onShow() {
    this.loadOrders(true);
    this.startCountdown();
  },

  onHide() {
    this.stopCountdown();
  },

  onUnload() {
    this.stopCountdown();
  },

  loadOrders(reset) {
    if (!auth.isLoggedIn()) {
      this.setData({ needLogin: true, loading: false, orders: [] });
      return;
    }
    if (!reset && (this.data.loadingMore || !this.data.hasMore)) return;
    const page = reset ? 1 : this.data.page + 1;
    const options = { category: 'mall' };
    const status = this.data.activeStatus;
    if (status !== 'all') options.status = status;
    this.setData({
      needLogin: false,
      loading: reset,
      loadingMore: !reset,
      error: reset ? '' : this.data.error
    });
    api
      .fetchOrders(page, PAGE_SIZE, options)
      .then(result => {
        const records = ((result && result.records) || []).map(order => mall.decorateOrder(order));
        const orders = reset ? records : this.data.orders.concat(records);
        const total = Number(result && result.total) || orders.length;
        this.setData({
          orders,
          page,
          loading: false,
          loadingMore: false,
          hasMore: orders.length < total && records.length > 0
        });
      })
      .catch(error => {
        this.setData({
          loading: false,
          loadingMore: false,
          error: (error && error.message) || '商城订单加载失败'
        });
      });
  },

  selectStatus(event) {
    const status = String(event.currentTarget.dataset.status || 'all');
    if (status === this.data.activeStatus) return;
    this.setData({ activeStatus: status, orders: [], page: 1, hasMore: true });
    this.loadOrders(true);
  },

  loadMore() {
    this.loadOrders(false);
  },

  // 仅对 SHIPPED 订单每秒重算 autoCompleteTime 倒计时，状态仍以后端为准。
  startCountdown() {
    this.stopCountdown();
    this.countdownTimer = setInterval(() => {
      if (!this.data.orders.length) return;
      this.setData({
        orders: this.data.orders.map(order => (order.status === 'SHIPPED' ? mall.decorateOrder(order) : order))
      });
    }, 1000);
  },

  stopCountdown() {
    if (this.countdownTimer) clearInterval(this.countdownTimer);
    this.countdownTimer = null;
  },

  openDetail(event) {
    const orderNo = String(event.currentTarget.dataset.orderNo || '').trim();
    if (!orderNo) return;
    wx.navigateTo({ url: `/packageMall/pages/order-detail/order-detail?orderNo=${orderNo}` });
  },

  findOrder(event) {
    const orderNo = String(event.currentTarget.dataset.orderNo || '').trim();
    return this.data.orders.find(order => String(order.orderNo) === orderNo);
  },

  continuePayment(event) {
    const order = this.findOrder(event);
    if (!order || this.data.actionOrderNo) return;
    const amountFen = Number(order.paidAmount || order.totalAmount) || 0;
    this.setData({ actionOrderNo: order.orderNo });
    wx.showLoading({ title: '正在支付', mask: true });
    const task =
      order.payChannel === 'STORED_VALUE'
        ? api.payOrderByBalance(order.orderNo)
        : requestOrderPayment(order.orderNo, amountFen, { maxAttempts: 10 });
    task
      .then(result => {
        wx.hideLoading();
        this.setData({ actionOrderNo: '' });
        const paid =
          order.payChannel === 'STORED_VALUE'
            ? mall.normalizeStatus(result && result.payStatus) === 'PAID'
            : Boolean(result && result.paid);
        wx.showToast({ title: paid ? '支付成功' : '订单待支付', icon: 'none' });
        this.loadOrders(true);
      })
      .catch(error => {
        wx.hideLoading();
        this.setData({ actionOrderNo: '' });
        wx.showToast({ title: (error && error.message) || '支付未完成', icon: 'none' });
      });
  },

  cancelOrder(event) {
    const order = this.findOrder(event);
    if (!order || this.data.actionOrderNo) return;
    const isPaid = order.status === 'PAID';
    wx.showModal({
      title: '取消订单',
      content: isPaid ? '确定取消订单？退款将原路退回。' : '确定取消该待支付订单吗？',
      success: ({ confirm }) => {
        if (!confirm) return;
        this.setData({ actionOrderNo: order.orderNo });
        wx.showLoading({ title: isPaid ? '退款申请中' : '取消中', mask: true });
        api
          .cancelOrder(order.orderNo)
          .then(() => {
            wx.hideLoading();
            this.setData({ actionOrderNo: '' });
            wx.showToast({ title: isPaid ? '已申请退款' : '订单已取消', icon: 'none' });
            this.loadOrders(true);
          })
          .catch(error => {
            wx.hideLoading();
            this.setData({ actionOrderNo: '' });
            wx.showToast({ title: (error && error.message) || '操作失败', icon: 'none' });
          });
      }
    });
  },

  confirmReceipt(event) {
    const order = this.findOrder(event);
    if (!order || this.data.actionOrderNo) return;
    wx.showModal({
      title: '确认收货',
      content: '确认已经收到商品了吗？确认后订单将完成。',
      success: ({ confirm }) => {
        if (!confirm) return;
        this.setData({ actionOrderNo: order.orderNo });
        wx.showLoading({ title: '确认中', mask: true });
        api
          .confirmMallReceipt(order.orderNo)
          .then(() => {
            wx.hideLoading();
            this.setData({ actionOrderNo: '' });
            wx.showToast({ title: '已确认收货', icon: 'success' });
            this.loadOrders(true);
          })
          .catch(error => {
            wx.hideLoading();
            this.setData({ actionOrderNo: '' });
            wx.showToast({ title: (error && error.message) || '确认失败', icon: 'none' });
          });
      }
    });
  },

  openAfterSales(event) {
    const orderNo = String(event.currentTarget.dataset.orderNo || '').trim();
    if (!orderNo) return;
    wx.navigateTo({
      url: `/packageMall/pages/after-sales/after-sales?orderNo=${encodeURIComponent(orderNo)}`
    });
  },
  retry() {
    this.loadOrders(true);
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadOrders(true), { reason: '登录后可查看商城订单' });
  }
});
