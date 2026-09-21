const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { orderCategories } = require('../../data/mock');
const { cancelOrderById, cancelPaidOrderById, filterOrders, getOrders, tickOrderCountdowns } = require('../../utils/orders');

const timeTabs = [
  { id: 'today', label: '今日订单' },
  { id: 'history', label: '历史订单' }
];

Page(
  withShare({
    data: {
      timeTabs,
      activeTimeGroup: 'history',
      orderCategories,
      activeCategory: 'all',
      orders: [],
      filteredOrders: []
    },
    onLoad() {
      // 订单列表从后端拉取（未登录时静默失败，保持本地展示）
      api
        .fetchOrders()
        .then(list => {
          if (Array.isArray(list)) this.setData({ remoteOrders: list });
        })
        .catch(() => null);      this.refreshOrders();
    },
    onShow() {
      if (this.getTabBar) this.getTabBar().setData({ selected: 3 });
      this.refreshOrders();
      this.startCountdown();
    },
    onHide() {
      this.stopCountdown();
    },
    onUnload() {
      this.stopCountdown();
    },
    refreshOrders() {
      const orders = getOrders();
      this.setData({
        orders,
        filteredOrders: filterOrders(orders, this.data.activeTimeGroup, this.data.activeCategory)
      });
    },
    startCountdown() {
      this.stopCountdown();
      this.countdownTimer = setInterval(() => {
        tickOrderCountdowns();
        this.refreshOrders();
      }, 1000);
    },
    stopCountdown() {
      if (this.countdownTimer) clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    },
    selectTimeGroup(event) {
      const group = event.currentTarget.dataset.group;
      this.setData({ activeTimeGroup: group }, () => this.refreshOrders());
    },
    selectCategory(event) {
      const { id } = event.currentTarget.dataset;
      this.setData({ activeCategory: id }, () => this.refreshOrders());
    },
    showInvoice() {
      wx.showToast({ title: '开发票暂未接入', icon: 'none' });
    },
    openOrderDetail(event) {
      const { id } = event.currentTarget.dataset;
      wx.navigateTo({ url: `/pages/order-detail/order-detail?id=${id}` });
    },
    cancelOrder(event) {
      const { id } = event.currentTarget.dataset;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该订单吗？',
        success: ({ confirm }) => {
          if (!confirm) return;
          cancelOrderById(id);
          this.refreshOrders();
          wx.showToast({ title: '订单已取消', icon: 'none' });
        }
      });
    },
    cancelPaidOrder(event) {
      const { id } = event.currentTarget.dataset;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该订单？款项将原路退回',
        success: ({ confirm }) => {
          if (!confirm) return;
          cancelPaidOrderById(id);
          this.refreshOrders();
          wx.showToast({ title: '已取消，退款原路退回', icon: 'none' });
        }
      });
    },
    handlePay() {
      wx.showToast({ title: '支付功能暂未接入', icon: 'none' });
    },
    showUnavailable() {
      wx.showToast({ title: '功能暂未接入', icon: 'none' });
    }
  })
);

