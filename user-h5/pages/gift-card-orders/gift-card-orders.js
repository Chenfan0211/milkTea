const { withShare } = require('../../utils/share');
const { cancelOrderById, getOrders, tickOrderCountdowns } = require('../../utils/orders');

const statusTabs = [
  { id: 'all', label: '全部' },
  { id: 'pending', label: '待支付' },
  { id: 'pending_verify', label: '待核销' },
  { id: 'completed', label: '已完成' },
  { id: 'canceled', label: '已取消' }
];

function matchStatus(order, statusId) {
  if (statusId === 'all') return true;
  if (statusId === 'pending') return order.isPendingPayment === true;
  if (statusId === 'pending_verify') return order.orderStatus === 'pending_verify';
  if (statusId === 'canceled') return order.isCanceled;
  if (statusId === 'completed')
    return !order.isPendingPayment && !order.isCanceled && order.orderStatus !== 'pending_verify';
  return false;
}

function filterGiftCardOrders(orders, keyword, statusId) {
  const normalized = String(keyword || '')
    .trim()
    .toLowerCase();
  return orders.filter(order => {
    if (!matchStatus(order, statusId)) return false;
    if (!normalized) return true;
    const title = String(order.title || '').toLowerCase();
    const orderNo = order.orderInfo && order.orderInfo.orderNo ? String(order.orderInfo.orderNo).toLowerCase() : '';
    return title.indexOf(normalized) !== -1 || orderNo.indexOf(normalized) !== -1;
  });
}

Page(
  withShare({
    data: {
      statusTabs,
      activeStatus: 'all',
      searchKeyword: '',
      allOrders: [],
      filteredOrders: []
    },
    onLoad(options) {
      const requested = options && options.status;
      if (statusTabs.some(tab => tab.id === requested)) {
        this.setData({ activeStatus: requested });
      }
      this.refresh();
    },
    onHide() {
      this.stopCountdown();
    },
    onUnload() {
      this.stopCountdown();
    },
    onShow() {
      this.refresh();
      this.startCountdown();
    },
    refresh() {
      const allOrders = getOrders().filter(order => order.category === 'gift-card');
      this.setData({
        allOrders,
        filteredOrders: filterGiftCardOrders(allOrders, this.data.searchKeyword, this.data.activeStatus)
      });
    },
    selectStatus(event) {
      const { id } = event.currentTarget.dataset;
      this.setData({ activeStatus: id }, () => this.applyFilter());
    },
    handleSearchInput(event) {
      this.setData({ searchKeyword: event.detail.value }, () => this.applyFilter());
    },
    clearSearch() {
      this.setData({ searchKeyword: '' }, () => this.applyFilter());
    },
    applyFilter() {
      this.setData({
        filteredOrders: filterGiftCardOrders(this.data.allOrders, this.data.searchKeyword, this.data.activeStatus)
      });
    },
    startCountdown() {
      this.stopCountdown();
      this.countdownTimer = setInterval(() => {
        tickOrderCountdowns();
        this.refresh();
      }, 1000);
    },
    stopCountdown() {
      if (this.countdownTimer) clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    },
    cancelOrder(event) {
      const { id } = event.currentTarget.dataset;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该礼品卡订单吗？',
        success: ({ confirm }) => {
          if (!confirm) return;
          const order = cancelOrderById(id);
          if (order) this.refresh();
          wx.showToast({ title: '订单已取消', icon: 'none' });
        }
      });
    },
    handlePay() {
      wx.showToast({ title: '支付功能暂未接入', icon: 'none' });
    },
    openOrderDetail(event) {
      const { id } = event.currentTarget.dataset;
      if (!id) return;
      wx.navigateTo({ url: '/pages/order-detail/order-detail?id=' + id });
    }
  })
);
