const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const loginGuard = require('../../utils/login-guard');
const { requestGiftCardPayment } = require('../../utils/gift-card-payment');
const { formatDateTime } = require('../../utils/date-format');
const { pickGiftCardRecords, resolveGiftCardDisplay } = require('../../utils/gift-card');

const statusTabs = [
  { id: 'all', label: '全部' },
  { id: 'pending', label: '待支付' },
  { id: 'pending_verify', label: '待核销' },
  { id: 'completed', label: '已完成' },
  { id: 'canceled', label: '已取消' }
];
function normalizeStatus(value) {
  return value === undefined || value === null ? '' : String(value).toUpperCase();
}

function matchStatus(order, statusId) {
  if (statusId === 'all') return true;
  if (statusId === 'pending') return order.isPendingPayment === true;
  if (statusId === 'pending_verify') return order.orderStatus === 'pending_verify';
  if (statusId === 'completed') return order.orderStatus === 'completed';
  if (statusId === 'canceled') return order.orderStatus === 'canceled';
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
    const orderNo = String(order.orderNo || '').toLowerCase();
    const cardNo = String(order.cardNo || '').toLowerCase();
    return title.indexOf(normalized) !== -1 || orderNo.indexOf(normalized) !== -1 || cardNo.indexOf(normalized) !== -1;
  });
}

// 后端 GiftCardOrder -> 前端订单卡片结构
function decorate(order) {
  const display = resolveGiftCardDisplay(order);
  const amount = Number(order.amount) || Number(order.salePrice) || 0;
  const payStatus = normalizeStatus(order.payStatus);
  const status = normalizeStatus(order.status);
  const verifyStatus = normalizeStatus(order.verifyStatus);
  const refundStatus = normalizeStatus(order.refundStatus || (order.refund && order.refund.status));
  const isPaid = payStatus === 'PAID';
  const isRefunded = payStatus === 'REFUNDED';
  const isCompleted = status === 'COMPLETED';
  const isCanceled = status === 'CANCELED';
  const isVerified = verifyStatus === 'VERIFIED';
  const isRefunding = refundStatus === 'REFUNDING';
  const isRefundFailed = !isRefunding && !isVerified && !isCanceled && refundStatus === 'FAILED';
  const isPendingPayment = !isCanceled && !isCompleted && payStatus === 'UNPAID';
  const isPendingVerify = !isCanceled && !isCompleted && !isPendingPayment;
  const canRefund = isPendingVerify && !isRefunding && !isRefundFailed && !isVerified;
  const canVerify = isPendingVerify && !isRefunding && !isRefundFailed && !isVerified;
  const refundButtonText = isRefundFailed ? '重试退款' : '申请退款';
  const orderStatus = isCanceled
    ? 'canceled'
    : isCompleted
      ? 'completed'
      : isPendingPayment
        ? 'pending_payment'
        : 'pending_verify';
  const cancelType = order.cancelType || (isPaid ? 'paid' : 'pending');
  const statusText = isCanceled ? '已取消' : isCompleted ? '已完成' : isPendingPayment ? '待支付' : '待核销';
  const refundHintText = isRefunding
    ? '退款处理中，暂时不能核销或重复申请退款'
    : isRefundFailed
      ? '退款失败，可重新发起退款'
      : '';
  return Object.assign({}, order, {
    id: order.id,
    orderNo: order.orderNo,
    title: display.name,
    coverImage: display.image,
    amountText: (amount / 100).toFixed(2),
    orderInfo: { orderNo: order.orderNo },
    payTimeText: formatDateTime(order.payTime),
    isPendingPayment,
    isPendingVerify,
    isRefunding,
    isRefundFailed,
    isCanceled,
    isVerified,
    isExchange: false,
    canCancel: isPendingPayment,
    canRefund,
    canVerify,
    refundButtonText,
    refundHintText,
    cancelType,
    orderStatus,
    statusText,
    countdownText: ''
  });
}

/**
 * 把「订单主键 -> 卡号」映射合并进订单列表。
 * 订单接口不下发卡号，卡号来自「我的礼品卡」（fetchMyGiftCards 返回含 orderId 与 cardNo），
 * 仅用于展示，不独立渲染卡区块，避免同一笔购买重复出现。
 */
function mergeCardNoIntoOrders(orders, cards) {
  const map = new Map();
  (cards || []).forEach(card => {
    if (card && card.orderId != null) map.set(String(card.orderId), card.cardNo || '');
  });
  return (orders || []).map(order =>
    Object.assign({}, order, {
      cardNo: map.get(String(order.id)) || order.cardNo || ''
    })
  );
}
function toRecordList(source) {
  return pickGiftCardRecords(source);
}

Page(
  withShare({
    data: {
      statusTabs,
      activeStatus: 'all',
      searchKeyword: '',
      allOrders: [],
      allCards: [],
      filteredOrders: [],
      loading: false,
      loadError: '',
      payingOrderNo: '',
      refundingOrderNo: ''
    },
    onLoad(options) {
      const requested = options && options.status;
      if (statusTabs.some(tab => tab.id === requested)) {
        this.setData({ activeStatus: requested });
      }
    },
    onShow() {
      this.refresh();
    },
    refresh() {
      this.setData({ loading: true, loadError: '' });
      const ordersTask = api
        .fetchGiftCardOrders()
        .then(orders => ({
          ok: true,
          records: (orders && Array.isArray(orders.records) ? orders.records : toRecordList(orders)).map(order =>
            decorate(order)
          )
        }))
        .catch(() => ({ ok: false, records: null }));
      // 卡号仅用于按订单主键合并展示，不独立渲染卡区块。
      const cardsTask = api
        .fetchMyGiftCards()
        .then(result => ({
          ok: true,
          records: toRecordList(result)
        }))
        .catch(() => ({ ok: false, records: null }));

      return Promise.all([ordersTask, cardsTask]).then(([ordersResult, cardsResult]) => {
        const orders = ordersResult.ok ? ordersResult.records : this.data.allOrders;
        const cards = cardsResult.ok ? cardsResult.records : this.data.allCards;
        const allOrders = mergeCardNoIntoOrders(orders, cards);
        const loadError = !ordersResult.ok || !cardsResult.ok ? '部分礼品卡数据加载失败，请重试' : '';
        this.setData(
          {
            allOrders,
            allCards: cards,
            loading: false,
            loadError
          },
          () => this.applyFilter()
        );
      });
    },
    retryLoad() {
      this.refresh();
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
      const orderNo = event.currentTarget.dataset.orderNo;
      if (!orderNo) return;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该未支付礼品卡订单吗？',
        success: ({ confirm }) => {
          if (!confirm) return;
          api
            .cancelGiftCardOrder(orderNo)
            .then(() => {
              wx.showToast({ title: '订单已取消', icon: 'none' });
              this.refresh();
            })
            .catch(error =>
              wx.showToast({
                title: (error && error.message) || '取消失败',
                icon: 'none'
              })
            );
        }
      });
    },
    handlePay(event) {
      const orderNo = event.currentTarget.dataset.orderNo;
      if (!orderNo) return;
      loginGuard.requirePhone(() => this.payOrder(orderNo), { reason: '支付礼品卡需要绑定手机号' });
    },
    payOrder(orderNo) {
      if (this.data.payingOrderNo) return Promise.resolve(false);
      this.setData({ payingOrderNo: orderNo });
      wx.showLoading({ title: '正在支付', mask: true });
      return requestGiftCardPayment(orderNo)
        .then(result => {
          wx.hideLoading();
          this.setData({ payingOrderNo: '' });
          if (result && result.paid) {
            wx.showToast({ title: '支付成功', icon: 'success' });
            this.refresh();
            return true;
          }
          wx.showToast({
            title: result && result.canceled ? '支付已取消，订单仍待支付' : '支付未完成，订单仍待支付',
            icon: 'none'
          });
          return false;
        })
        .catch(error => {
          wx.hideLoading();
          this.setData({ payingOrderNo: '' });
          wx.showToast({
            title: (error && error.message) || '支付失败，请稍后重试',
            icon: 'none'
          });
          return false;
        });
    },
    requestRefund(event) {
      const orderNo = event.currentTarget.dataset.orderNo;
      if (!orderNo || this.data.refundingOrderNo) return;
      wx.showModal({
        title: '申请退款',
        content: '退款将按微信原路退回，确认申请全额退款吗？',
        success: ({ confirm }) => {
          if (!confirm) return;
          this.setData({ refundingOrderNo: orderNo });
          wx.showLoading({ title: '提交退款中', mask: true });
          api
            .refundGiftCard(orderNo, '用户申请退款')
            .then(() => {
              wx.hideLoading();
              this.setData({ refundingOrderNo: '' });
              wx.showToast({ title: '退款申请已提交', icon: 'none' });
              this.refresh();
            })
            .catch(error => {
              wx.hideLoading();
              this.setData({ refundingOrderNo: '' });
              wx.showToast({
                title: (error && error.message) || '退款申请失败',
                icon: 'none'
              });
            });
        }
      });
    }
  })
);
