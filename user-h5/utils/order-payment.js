const api = require('./api');

const PAY_POLL_INTERVAL_MS = 1200;
const PAY_POLL_MAX_ATTEMPTS = 8;

function normalizeStatus(value) {
  return value === undefined || value === null ? '' : String(value).toUpperCase();
}

function isPaid(order) {
  return normalizeStatus(order && order.payStatus) === 'PAID';
}

function isClosed(order) {
  const payStatus = normalizeStatus(order && order.payStatus);
  const status = normalizeStatus(order && order.status);
  return payStatus === 'REFUNDED' || status === 'CANCELED' || status === 'CLOSED';
}

function paymentResult(order, extra) {
  return Object.assign(
    {
      paid: isPaid(order),
      closed: isClosed(order),
      canceled: false,
      order
    },
    extra || {}
  );
}

function wait(intervalMs) {
  return new Promise(resolve => setTimeout(resolve, intervalMs));
}

/**
 * 轮询服务端订单状态。wx.requestPayment 成功只代表收银台完成，
 * 最终必须以后端 payStatus=PAID 为准。
 */
function pollOrderStatus(orderNo, options) {
  const config = options || {};
  const intervalMs = Number(config.intervalMs) || PAY_POLL_INTERVAL_MS;
  const maxAttempts = Number(config.maxAttempts) || PAY_POLL_MAX_ATTEMPTS;
  let attempts = 0;
  let lastError = null;

  const tick = () =>
    api
      .fetchOrderDetail(orderNo)
      .then(order => {
        if (!order) throw new Error('订单查询失败');
        if (isPaid(order)) return paymentResult(order);
        if (isClosed(order)) return paymentResult(order, { closed: true });
        attempts += 1;
        if (attempts >= maxAttempts) return paymentResult(order);
        return wait(intervalMs).then(tick);
      })
      .catch(error => {
        lastError = error;
        attempts += 1;
        if (attempts >= maxAttempts) {
          return { paid: false, closed: false, canceled: false, order: null, error: lastError };
        }
        return wait(intervalMs).then(tick);
      });

  return tick();
}

function requestWxPayment(params) {
  if (typeof wx === 'undefined' || !wx.requestPayment) {
    return Promise.reject(new Error('当前环境不支持微信支付'));
  }
  return new Promise((resolve, reject) => {
    wx.requestPayment({
      timeStamp: params.timeStamp,
      nonceStr: params.nonceStr,
      package: params.package,
      signType: params.signType,
      paySign: params.paySign,
      success: () => resolve(true),
      fail: error => {
        const failure = new Error((error && error.errMsg) || '支付未完成');
        failure.canceled = /cancel/i.test(failure.message);
        reject(failure);
      }
    });
  });
}

/**
 * 发起一单点单订单的微信支付。
 *
 * 流程：payOrder（服务端统一下单）-> wx.requestPayment -> 轮询订单状态。
 * 用户取消或支付失败时不取消订单，保留为待支付状态，供订单详情页继续支付。
 *
 * 兼容 mock 通道：后台 PAY_CHANNEL=mock 时 /pay 直接返回「已支付」的订单 DTO
 * （无 params），此时跳过收银台、直接轮询确认状态后视为支付成功。
 *
 * @param {string} orderNo 订单号
 * @param {number} amountFen 金额（分），与订单实付一致
 * @returns {Promise<{paid:boolean, closed:boolean, canceled:boolean, order:object}>}
 */
function requestOrderPayment(orderNo, amountFen, options) {
  const no = String(orderNo || '').trim();
  if (!no) return Promise.reject(new Error('订单号不能为空'));

  return api.payOrder(no, amountFen, 'WXPAY').then(result => {
    const params = (result && result.params) || result;
    // mock/开发通道可能不返回真实收银台参数，只能查单确认后端状态。
    if (!params || !params.timeStamp) {
      return pollOrderStatus(no, options);
    }
    return requestWxPayment(params)
      .then(() => pollOrderStatus(no, options))
      .catch(error =>
        api
          .fetchOrderDetail(no)
          .then(order => paymentResult(order, { canceled: Boolean(error && error.canceled) }))
          .catch(() => {
            throw error;
          })
      );
  });
}

module.exports = {
  PAY_POLL_INTERVAL_MS,
  PAY_POLL_MAX_ATTEMPTS,
  pollOrderStatus,
  requestOrderPayment
};
