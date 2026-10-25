/**
 * 微信小程序订阅消息原生授权。
 *
 * 微信原生弹层由微信控制；单次最多申请 3 个模板。业务侧只按场景传入相关模板，
 * 失败、拒绝或不支持时都静默返回，不能阻断支付、签到、兑换、充值等主流程。
 */
const TEMPLATE_IDS = {
  ORDER: 'W-_sFdE4DpQbfs5aeXASyEvVIVkFBYz65Qjl8lQQJWM',
  POINTS_CHANGE: 'W69-LPXpDxs7pPiN_0I_75I0XqXJWPkL4XYCS26CopM',
  POINTS_EXCHANGE: '6DKXp9_Bx4gYaj0CaUeTp6rXyKIF3AZrrmSR5CwHjfE',
  STORED_VALUE_RECHARGE: 'uvD7jHuIZ1bpXIA-HfqHXfScW0j2zIYBCYWHR7emFJI',
  BALANCE_CHANGE: 'uvD7jHuIZ1bpXIA-HfqHXQGw6MCweamgsPFK-MIVne0',
  COUPON_ARRIVAL: 'en0sN5PvHmq9DyiZIjaH_x5-bGpisdD3boCqMa_wx5g',
  COUPON_EXPIRY: 'oaeDu3ZTFfzrrPQvFliAMRHdMNg19KW2V8xhvCU5MSo'
};

const ITEM_TEMPLATE_IDS = {
  order: TEMPLATE_IDS.ORDER,
  pointsChange: TEMPLATE_IDS.POINTS_CHANGE,
  pointsExchange: TEMPLATE_IDS.POINTS_EXCHANGE,
  storedValueRecharge: TEMPLATE_IDS.STORED_VALUE_RECHARGE,
  balanceChange: TEMPLATE_IDS.BALANCE_CHANGE,
  couponArrival: TEMPLATE_IDS.COUPON_ARRIVAL,
  couponExpiry: TEMPLATE_IDS.COUPON_EXPIRY
};

const ORDER_TEMPLATE_ID = TEMPLATE_IDS.ORDER;
const MAX_TEMPLATES_PER_REQUEST = 3;

function logResult(result) {
  try {
    console.info('[subscribe-message] 订阅授权结果', result || {});
  } catch (error) {
    // 日志失败不影响业务
  }
}

function requestBatch(tmplIds) {
  return new Promise(resolve => {
    let settled = false;
    const finish = result => {
      if (settled) return;
      settled = true;
      logResult(result);
      resolve(result || {});
    };
    if (typeof wx === 'undefined' || typeof wx.requestSubscribeMessage !== 'function') {
      finish({ errMsg: 'requestSubscribeMessage unsupported' });
      return;
    }
    try {
      wx.requestSubscribeMessage({
        tmplIds,
        success: finish,
        fail: error => finish(error || {})
      });
    } catch (error) {
      finish({ errMsg: (error && error.message) || 'requestSubscribeMessage error' });
    }
  });
}

/** 申请一组模板；单次最多 3 个，超过时顺序分批且永不 reject。 */
function requestSubscribe(tmplIds) {
  const ids = Array.from(new Set((Array.isArray(tmplIds) ? tmplIds : []).filter(Boolean)));
  if (!ids.length) return Promise.resolve([]);
  const batches = [];
  for (let index = 0; index < ids.length; index += MAX_TEMPLATES_PER_REQUEST) {
    batches.push(ids.slice(index, index + MAX_TEMPLATES_PER_REQUEST));
  }
  return batches.reduce(
    (promise, batch) =>
      promise.then(results =>
        requestBatch(batch).then(result => {
          results.push(result);
          return results;
        })
      ),
    Promise.resolve([])
  );
}

function resolveTemplateId(keyOrTemplateId) {
  if (typeof keyOrTemplateId !== 'string') return '';
  if (ITEM_TEMPLATE_IDS[keyOrTemplateId]) return ITEM_TEMPLATE_IDS[keyOrTemplateId];
  return Object.values(TEMPLATE_IDS).includes(keyOrTemplateId) ? keyOrTemplateId : '';
}

function requestOrderSubscribe() {
  return requestSubscribe([ORDER_TEMPLATE_ID]).then(results => results[0] || {});
}

function requestContextualSubscribe(keys) {
  const ids = (Array.isArray(keys) ? keys : [keys])
    .map(resolveTemplateId)
    .filter(Boolean)
    .slice(0, MAX_TEMPLATES_PER_REQUEST);
  return requestSubscribe(ids).then(results => results[0] || {});
}

module.exports = {
  TEMPLATE_IDS,
  ORDER_TEMPLATE_ID,
  MAX_TEMPLATES_PER_REQUEST,
  requestSubscribe,
  requestOrderSubscribe,
  requestContextualSubscribe
};
