import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const utilPath = path.join(root, 'utils/subscribe-message.js');
const source = fs.readFileSync(utilPath, 'utf8');

assert.ok(!source.includes('fetchSubscriptionSettings'), '不得再读取自定义订阅状态');
assert.ok(!source.includes('openSubscriptionSettings'), '不得再跳转自定义订阅设置动作');
assert.ok(!source.includes('requestSingleSubscription'), '不得再暴露逐项开关授权');
assert.ok(!fs.existsSync(path.join(root, 'pages/subscribe-message')), '自定义订阅列表页必须删除');
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(!appJson.pages.includes('pages/subscribe-message/subscribe-message'), 'app.json 不得注册订阅列表页');

const subscribe = require(utilPath);
assert.equal(subscribe.TEMPLATE_IDS.ORDER, 'W-_sFdE4DpQbfs5aeXASyEvVIVkFBYz65Qjl8lQQJWM');
assert.equal(subscribe.TEMPLATE_IDS.COUPON_EXPIRY, 'oaeDu3ZTFfzrrPQvFliAMRHdMNg19KW2V8xhvCU5MSo');

function withWx(wx, run) {
  const had = Object.prototype.hasOwnProperty.call(globalThis, 'wx');
  const previous = globalThis.wx;
  if (wx === null) delete globalThis.wx;
  else globalThis.wx = wx;
  return Promise.resolve()
    .then(run)
    .finally(() => {
      if (had) globalThis.wx = previous;
      else delete globalThis.wx;
    });
}

await withWx(
  {
    requestSubscribeMessage(options) {
      assert.deepEqual(
        options.tmplIds,
        [
          subscribe.TEMPLATE_IDS.STORED_VALUE_RECHARGE,
          subscribe.TEMPLATE_IDS.BALANCE_CHANGE,
          subscribe.TEMPLATE_IDS.COUPON_ARRIVAL
        ],
        '充值时三个相关模板必须在同一个原生弹层'
      );
      options.success(Object.fromEntries(options.tmplIds.map(id => [id, 'accept'])));
    }
  },
  async () => {
    const result = await subscribe.requestContextualSubscribe([
      'storedValueRecharge',
      'balanceChange',
      'couponArrival'
    ]);
    assert.deepEqual(Object.keys(result).length, 3);
  }
);

await withWx(
  {
    requestSubscribeMessage(options) {
      assert.deepEqual(options.tmplIds, [subscribe.TEMPLATE_IDS.POINTS_EXCHANGE, subscribe.TEMPLATE_IDS.POINTS_CHANGE]);
      options.success({});
    }
  },
  async () => {
    await subscribe.requestContextualSubscribe(['pointsExchange', 'pointsChange']);
  }
);

await withWx(
  {
    requestSubscribeMessage(options) {
      assert.equal(options.tmplIds.length, 3, '上下文授权最多一次弹 3 个');
      options.success({});
    }
  },
  async () => {
    await subscribe.requestContextualSubscribe(['order', 'pointsChange', 'pointsExchange', 'couponArrival']);
  }
);

await withWx(
  {
    requestSubscribeMessage(options) {
      options.fail({ errMsg: 'requestSubscribeMessage:fail mock' });
    }
  },
  async () => {
    await assert.doesNotReject(() => subscribe.requestOrderSubscribe(), '拒绝不能阻断支付');
  }
);

const confirmSource = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
const detailSource = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.js'), 'utf8');
const signinSource = fs.readFileSync(path.join(root, 'pages/points-signin/points-signin.js'), 'utf8');
const exchangeSource = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.js'), 'utf8');
const storedValueSource = fs.readFileSync(path.join(root, 'pages/stored-value/stored-value.js'), 'utf8');
assert.ok(confirmSource.includes('requestOrderSubscribe'), '确认页必须先申请订单订阅');
assert.ok(detailSource.includes('requestOrderSubscribe'), '订单详情继续支付必须先申请订单订阅');
assert.ok(signinSource.includes("requestContextualSubscribe(['pointsChange']"), '签到申请积分变更');
assert.ok(
  exchangeSource.includes("requestContextualSubscribe(['pointsExchange', 'pointsChange']"),
  '兑换同时申请兑换与积分变更'
);
assert.ok(
  storedValueSource.includes("requestContextualSubscribe(['storedValueRecharge', 'balanceChange', 'couponArrival']"),
  '充值三模板合并弹层'
);

console.log('订阅原生授权与场景分组契约测试通过');
