const { formatOrderAmount } = require('../data/mock');
const api = require('./api');

let orderStore = [];

function cloneOrder(order) {
  return Object.assign({}, order, {
    items: Array.isArray(order.items) ? order.items.map(item => Object.assign({}, item)) : [],
    orderInfo: Object.assign({}, order.orderInfo || {})
  });
}

/**
 * 从后端拉取我的订单并刷新本地镜像。
 * 页面 onShow 调用；拉取失败时保留上一次结果。
 */
function refreshOrdersFromRemote() {
  return api
    .fetchOrders()
    .then(list => {
      if (Array.isArray(list)) orderStore = list.map(cloneOrder);
      return getOrders();
    })
    .catch(() => getOrders());
}

/**
 * 直接注入订单镜像（仅供测试使用）。
 * 生产代码请使用 refreshOrdersFromRemote()。
 */
function setOrdersForTest(list) {
  orderStore = (Array.isArray(list) ? list : []).map(cloneOrder);
  return getOrders();
}

/** 重置本地订单镜像（登录态切换或退出登录时调用）。 */
function resetOrders() {
  orderStore = [];
}

function formatCountdown(seconds) {
  const safeSeconds = Math.max(0, Math.floor(Number(seconds) || 0));
  const minutes = Math.floor(safeSeconds / 60);
  const remainder = safeSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

function decorateOrder(order) {
  const remainingSeconds = Math.max(0, Number(order.remainingSeconds || 0));
  const isCanceled = order.orderStatus === 'canceled' || order.status === '已取消';
  const isPendingPayment = order.orderStatus === 'pending_payment' && remainingSeconds > 0 && !isCanceled;
  const cancelType = isCanceled
    ? order.cancelType || (order.orderInfo && order.orderInfo.payMethod === '未支付' ? 'pending' : 'paid')
    : '';
  const firstItem = order.items && order.items[0] ? order.items[0] : {};
  const previewItems = Array.isArray(order.items) ? order.items.slice(0, 2) : [];
  return Object.assign({}, order, {
    timeGroup: order.timeGroup || 'history',
    remainingSeconds,
    isPendingPayment,
    isCanceled,
    cancelType,
    statusText: isPendingPayment ? '待支付' : isCanceled && order.category === 'gift-card' ? (cancelType === 'pending' ? '待支付取消' : '已支付取消') : isCanceled ? '已取消' : order.status,
    countdownText: formatCountdown(remainingSeconds),
    amountText: formatOrderAmount(order.amount),
    firstItem,
    previewItems,
    title: order.title || order.storeName || firstItem.name || '订单',
    coverImage: order.coverImage || firstItem.image || ''
  });
}

function sortOrders(list) {
  return list.slice().sort((left, right) => {
    if (left.isPendingPayment !== right.isPendingPayment) return left.isPendingPayment ? -1 : 1;
    if (left.isPendingPayment && right.isPendingPayment) return left.remainingSeconds - right.remainingSeconds;
    const leftTime = left.orderInfo && left.orderInfo.createdAt ? left.orderInfo.createdAt : '';
    const rightTime = right.orderInfo && right.orderInfo.createdAt ? right.orderInfo.createdAt : '';
    return rightTime.localeCompare(leftTime);
  });
}

function getOrders() {
  return sortOrders(orderStore.map(decorateOrder));
}

function getOrderById(id) {
  const order = orderStore.find(item => item.id === id);
  return order ? decorateOrder(order) : null;
}

function tickOrderCountdowns() {
  orderStore = orderStore.map(order => {
    if (order.orderStatus !== 'pending_payment') return order;
    const remainingSeconds = Math.max(0, Number(order.remainingSeconds || 0) - 1);
    if (remainingSeconds === 0) {
      return Object.assign({}, order, {
        remainingSeconds: 0,
        status: '已取消',
        orderStatus: 'canceled',
        statusTitle: order.category === 'gift-card' ? '待支付取消' : '已取消',
        statusNote: '支付超时，订单已自动取消',
        cancelType: 'pending'
      });
    }
    return Object.assign({}, order, { remainingSeconds });
  });
  return getOrders();
}

function cancelOrderById(id) {
  orderStore = orderStore.map(order => {
    if (order.id !== id || order.orderStatus !== 'pending_payment') return order;
    return Object.assign({}, order, {
      remainingSeconds: 0,
      status: '已取消',
      orderStatus: 'canceled',
      statusTitle: order.category === 'gift-card' ? '待支付取消' : '已取消',
      statusNote: '订单已取消',
      cancelType: 'pending'
    });
  });
  return getOrderById(id);
}

function cancelPaidOrderById(id) {
  orderStore = orderStore.map(order => {
    if (order.id !== id || order.orderStatus !== 'pending_verify' || order.category !== 'store') return order;
    return Object.assign({}, order, {
      status: '已取消',
      orderStatus: 'canceled',
      statusTitle: '已支付取消',
      statusNote: '订单已取消，退款将原路退回',
      cancelType: 'paid',
      refundAmount: order.amount
    });
  });
  return getOrderById(id);
}

function addGiftCardOrder(order) {
  if (!order || !order.id) return null;
  orderStore = [cloneOrder(order)].concat(orderStore);
  return getOrderById(order.id);
}

// 门店核销兑换后，将匹配自提码的礼品卡订单置为已完成。
function markOrderVerified(pickupCode) {
  const code = String(pickupCode || '');
  if (!code) return null;
  let updated = null;
  orderStore = orderStore.map(order => {
    if (order.category !== 'gift-card' || order.pickupCode !== code) return order;
    updated = Object.assign({}, order, {
      status: '已完成',
      orderStatus: 'completed',
      statusTitle: '已完成',
      statusNote: '核销成功，感谢您的兑换'
    });
    return updated;
  });
  return updated;
}

function filterOrders(list, timeGroup, category) {
  return list.filter(order => order.timeGroup === timeGroup && (category === 'all' || order.category === category));
}

resetOrders();

module.exports = {
  addGiftCardOrder,
  cancelOrderById,
  decorateOrder,
  filterOrders,
  formatCountdown,
  getOrderById,
  getOrders,
  markOrderVerified,
  refreshOrdersFromRemote,
  setOrdersForTest,
  resetOrders,
  tickOrderCountdowns
};
