const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { formatDateTime } = require('../../utils/date-format');

const statusTabs = [
  { id: 'all', label: '全部' },
  { id: 'pending', label: '待支付' },
  { id: 'pending_verify', label: '待核销' },
  { id: 'completed', label: '已核销' },
  { id: 'canceled', label: '已取消' }
];

function matchStatus(order, statusId) {
  if (statusId === 'all') return true;
  if (statusId === 'pending') return order.isPendingPayment === true;
  if (statusId === 'pending_verify') return order.orderStatus === 'pending_verify';
  if (statusId === 'completed') return order.orderStatus === 'completed';
  if (statusId === 'canceled') return order.orderStatus === 'canceled';
  return false;
}

function filterGiftCardOrders(orders, keyword, statusId) {
  const normalized = String(keyword || '').trim().toLowerCase();
  return orders.filter(order => {
    if (!matchStatus(order, statusId)) return false;
    if (!normalized) return true;
    const title = String(order.title || '').toLowerCase();
    const orderNo = String(order.orderNo || '').toLowerCase();
    return title.indexOf(normalized) !== -1 || orderNo.indexOf(normalized) !== -1;
  });
}

// 后端 GiftCardOrder -> 前端订单卡片结构
function decorate(order, denom) {
  const amount = Number(order.amount) || 0;
  const isPaid = order.payStatus === 'PAID';
  const isCanceled = order.status === 'CANCELED';
  const isVerified = order.status === 'VERIFIED' || order.verifyStatus === 'VERIFIED';
  // 待支付：未支付且未取消；待核销：已支付且未核销未取消
  const isPendingPayment = !isPaid && !isCanceled;
  const isPendingVerify = isPaid && !isCanceled && !isVerified;
  const orderStatus = isCanceled ? 'canceled' : isVerified ? 'completed' : isPendingPayment ? 'pending_payment' : 'pending_verify';
  const cancelType = order.cancelType || (isPaid ? 'paid' : 'pending');
  const statusText = isPendingPayment
    ? '待支付'
    : isCanceled
      ? (cancelType === 'pending' ? '待支付取消' : '已支付取消')
      : isVerified
        ? '已核销'
        : '待核销';
  return Object.assign({}, order, {
    id: order.id,
    orderNo: order.orderNo,
    title: (denom && (denom.cardName || denom.name)) || '礼品卡',
    coverImage: (denom && denom.cardImage) || '/assets/images/3x/gift-card-matcha.jpg',
    amountText: (amount / 100).toFixed(2),
    orderInfo: { orderNo: order.orderNo },
    payTime: formatDateTime(order.payTime),
    isPendingPayment,
    isPendingVerify,
    isCanceled,
    isVerified,
    isExchange: false,
    cancelType,
    orderStatus,
    statusText,
    countdownText: ''
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
    onShow() {
      this.refresh();
    },
    refresh() {
      Promise.all([api.fetchGiftCardOrders(), api.fetchGiftCardDenominations()])
        .then(([orders, denominations]) => {
          const denomMap = {};
          (Array.isArray(denominations) ? denominations : []).forEach(d => {
            denomMap[d.id] = d;
          });
          const allOrders = (Array.isArray(orders) ? orders : []).map(o => decorate(o, denomMap[o.denominationId]));
          this.setData({
            allOrders,
            filteredOrders: filterGiftCardOrders(allOrders, this.data.searchKeyword, this.data.activeStatus)
          });
        })
        .catch(() => {
          this.setData({ allOrders: [], filteredOrders: [] });
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
    cancelOrder(event) {
      const { id } = event.currentTarget.dataset;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该礼品卡订单吗？',
        success: ({ confirm }) => {
          if (!confirm) return;
          api
            .cancelGiftCardOrder(id)
            .then(() => {
              wx.showToast({ title: '订单已取消', icon: 'none' });
              this.refresh();
            })
            .catch(() => wx.showToast({ title: '取消失败', icon: 'none' }));
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
