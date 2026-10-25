import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(root, '..');
const require = createRequire(import.meta.url);

const TEMPLATE_ID = 'W-_sFdE4DpQbfs5aeXASyEvVIVkFBYz65Qjl8lQQJWM';
const utilPath = path.join(root, 'utils/subscribe-message.js');

/**
 * 下单订阅消息契约测试：
 * - 小程序端模板 ID 必须与 trade-service 默认配置一致；
 * - 确认页与订单详情「继续支付」都必须申请订阅授权；
 * - 订阅授权失败/不支持时不能 reject，不能阻断支付主流程。
 */

const utilSource = fs.readFileSync(utilPath, 'utf8');
assert.ok(utilSource.includes(TEMPLATE_ID), 'subscribe-message.js 必须内置订单订阅模板 ID');
assert.ok(utilSource.includes('requestSubscribeMessage'), '必须调用 wx.requestSubscribeMessage');

const appYml = fs.readFileSync(path.join(repoRoot, 'trade-service/src/main/resources/application.yml'), 'utf8');
assert.ok(
  new RegExp(`template-id:\\s*\\$\\{WX_ORDER_SUBSCRIBE_TEMPLATE_ID:${TEMPLATE_ID}\\}`).test(appYml),
  'trade-service application.yml 的默认订阅模板 ID 必须与小程序端一致'
);

const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
const detailJs = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.js'), 'utf8');
assert.ok(
  /handleSubmit\(\)\s*\{[\s\S]*?requestOrderSubscribe/.test(confirmJs),
  '确认页 handleSubmit 必须先申请订阅授权'
);
assert.ok(/handlePay\(\)\s*\{[\s\S]*?requestOrderSubscribe/.test(detailJs), '详情页 handlePay 必须先申请订阅授权');

const subscribe = require(utilPath);
assert.equal(subscribe.ORDER_TEMPLATE_ID, TEMPLATE_ID, '导出的模板 ID 必须正确');

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
      assert.deepEqual(options.tmplIds, [TEMPLATE_ID], 'tmplIds 必须只传订单订阅模板');
      options.success({ [TEMPLATE_ID]: 'accept' });
    }
  },
  async () => {
    const result = await subscribe.requestOrderSubscribe();
    assert.equal(result[TEMPLATE_ID], 'accept', 'accept 结果必须原样返回供日志排查');
  }
);

await withWx(
  {
    requestSubscribeMessage(options) {
      options.fail({ errMsg: 'requestSubscribeMessage:fail mock' });
    }
  },
  async () => {
    await assert.doesNotReject(
      () => subscribe.requestOrderSubscribe(),
      '用户拒绝或接口失败时不得 reject，否则会阻断支付'
    );
  }
);

await withWx({}, async () => {
  await assert.doesNotReject(() => subscribe.requestOrderSubscribe(), '基础库不支持时必须静默跳过');
});

await withWx(null, async () => {
  await assert.doesNotReject(() => subscribe.requestOrderSubscribe(), '非微信环境必须静默跳过');
});

console.log('下单订阅消息契约测试通过');
