const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const { requestOrderPayment } = require('../../../utils/order-payment');
const mall = require('../../utils/mall');

Page({
  data: {
    orderNo: '',
    order: null,
    loading: true,
    needLogin: false,
    error: '',
    actionBusy: false
  },

  onLoad(options) {
    const orderNo = String((options && options.orderNo) || '').trim();
    this.setData({ orderNo });
    if (!orderNo) {
      this.setData({ loading: false, error: '订单号缺失' });
      return;
    }
    this.loadOrder();
  },

  onShow() {
    if (this.data.orderNo && auth.isLoggedIn()) this.loadOrder();
    this.startCountdown();
  },

  onHide() {
    this.stopCountdown();
  },

  onUnload() {
    this.stopCountdown();
  },

  loadOrder() {
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, needLogin: true, error: '' });
      return;
    }
    this.setData({ loading: !this.data.order, needLogin: false, error: '' });
    api
      .fetchOrderDetail(this.data.orderNo)
      .then(result => {
        if (!result || result.category !== 'mall' || result.orderType !== 'MALL') {
          throw new Error('该订单不是商城订单');
        }
        this.setData({
          order: mall.decorateOrder(result),
          loading: false,
          error: ''
        });
      })
      .catch(error => {
        this.setData({
          loading: false,
          error: (error && error.message) || '订单详情加载失败'
        });
      });
  },

  // SHIPPED 状态每秒根据 autoCompleteTime 重算展示倒计时。
  startCountdown() {
    this.stopCountdown();
    this.countdownTimer = setInterval(() => {
      if (!this.data.order || this.data.order.status !== 'SHIPPED') return;
      this.setData({ order: mall.decorateOrder(this.data.order) });
    }, 1000);
  },

  stopCountdown() {
    if (this.countdownTimer) clearInterval(this.countdownTimer);
    this.countdownTimer = null;
  },

  continuePayment() {
    const order = this.data.order;
    if (!order || this.data.actionBusy) return;
    const amountFen = Number(order.paidAmount || order.totalAmount) || 0;
    this.setData({ actionBusy: true });
    wx.showLoading({ title: '正在支付', mask: true });
    const task =
      order.payChannel === 'STORED_VALUE'
        ? api.payOrderByBalance(order.orderNo)
        : requestOrderPayment(order.orderNo, amountFen, { maxAttempts: 10 });
    task
      .then(result => {
        wx.hideLoading();
        this.setData({ actionBusy: false });
        const paid =
          order.payChannel === 'STORED_VALUE'
            ? mall.normalizeStatus(result && result.payStatus) === 'PAID'
            : Boolean(result && result.paid);
        wx.showToast({ title: paid ? '支付成功' : '订单待支付', icon: 'none' });
        this.loadOrder();
      })
      .catch(error => {
        wx.hideLoading();
        this.setData({ actionBusy: false });
        wx.showToast({ title: (error && error.message) || '支付未完成', icon: 'none' });
      });
  },

  cancelOrder() {
    const order = this.data.order;
    if (!order || this.data.actionBusy) return;
    const isPaid = order.status === 'PAID';
    wx.showModal({
      title: '取消订单',
      content: isPaid ? '确定取消订单？退款将原路退回。' : '确定取消该待支付订单吗？',
      success: ({ confirm }) => {
        if (!confirm) return;
        this.setData({ actionBusy: true });
        wx.showLoading({ title: isPaid ? '退款申请中' : '取消中', mask: true });
        api
          .cancelOrder(order.orderNo)
          .then(() => {
            wx.hideLoading();
            this.setData({ actionBusy: false });
            wx.showToast({ title: isPaid ? '已申请退款' : '订单已取消', icon: 'none' });
            this.loadOrder();
          })
          .catch(error => {
            wx.hideLoading();
            this.setData({ actionBusy: false });
            wx.showToast({ title: (error && error.message) || '操作失败', icon: 'none' });
          });
      }
    });
  },

  confirmReceipt() {
    const order = this.data.order;
    if (!order || this.data.actionBusy) return;
    wx.showModal({
      title: '确认收货',
      content: '确认已经收到商品了吗？确认后订单将完成。',
      success: ({ confirm }) => {
        if (!confirm) return;
        this.setData({ actionBusy: true });
        wx.showLoading({ title: '确认中', mask: true });
        api
          .confirmMallReceipt(order.orderNo)
          .then(() => {
            wx.hideLoading();
            this.setData({ actionBusy: false });
            wx.showToast({ title: '已确认收货', icon: 'success' });
            this.loadOrder();
          })
          .catch(error => {
            wx.hideLoading();
            this.setData({ actionBusy: false });
            wx.showToast({ title: (error && error.message) || '确认失败', icon: 'none' });
          });
      }
    });
  },

  openAfterSale() {
    const order = this.data.order;
    if (!order) return;
    wx.navigateTo({
      url: `/packageMall/pages/after-sale/after-sale?orderNo=${encodeURIComponent(order.orderNo)}`
    });
  },

  openAfterSales() {
    const order = this.data.order;
    if (!order) return;
    wx.navigateTo({
      url: `/packageMall/pages/after-sales/after-sales?orderNo=${encodeURIComponent(order.orderNo)}`
    });
  },

  copyTrackingNo() {
    const trackingNo = this.data.order && this.data.order.shipping && this.data.order.shipping.trackingNo;
    if (!trackingNo) return;
    wx.setClipboardData({
      data: String(trackingNo),
      success: () => wx.showToast({ title: '单号已复制', icon: 'none' })
    });
  },

  openOrders() {
    wx.navigateTo({ url: '/packageMall/pages/orders/orders' });
  },

  retry() {
    this.loadOrder();
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadOrder(), { reason: '登录后可查看订单详情' });
  }
});
