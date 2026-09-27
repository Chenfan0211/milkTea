import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 储值订单展示字段回归：封面图、未支付倒计时、取消/支付按钮条件。
 */

globalThis.getApp = () => ({ globalData: {} });
globalThis.wx = { showToast() {}, showModal() {}, navigateTo() {}, navigateBack() {}, showShareMenu() {} };

const orders = require(path.join(root, 'utils/orders.js'));
const NOW = new Date('2026-09-27T17:00:00').getTime();

// 未支付储值订单：创建于 16:50（10 分钟前），应剩余约 5 分钟
const unpaid = orders.decorateOrder(
  orders.normalizeAuxOrder({
    id: 1,
    orderNo: 'CZ20260927165001',
    amount: 20000,
    payStatus: 'UNPAID',
    createTime: '2026-09-27 16:50:00'
  }, 'stored-value'),
  NOW
);

assert.equal(unpaid.coverImage, '/assets/images/3x/stored-value-banner.jpg', '未支付储值订单必须有封面图');
assert.equal(unpaid.isPendingPayment, true, '未支付储值订单必须判定为待支付（显示倒计时和按钮）');
assert.ok(unpaid.countdownText && /^\d{2}:\d{2}$/.test(unpaid.countdownText), '倒计时必须为 mm:ss 格式');
assert.ok(unpaid.remainingSeconds > 0, '未支付储值订单必须有剩余支付秒数');
assert.equal(unpaid.statusText, '未支付', '未支付储值订单状态文案应为未支付（业务约定）');

// 已支付储值订单：不得显示倒计时和取消/支付按钮
const paid = orders.decorateOrder(
  orders.normalizeAuxOrder({
    id: 2,
    orderNo: 'CZ20260927160001',
    amount: 20000,
    payStatus: 'PAID',
    createTime: '2026-09-27 16:00:00'
  }, 'stored-value'),
  NOW
);
assert.equal(paid.isPendingPayment, false, '已支付储值订单不得判定为待支付');
assert.equal(paid.coverImage, '/assets/images/3x/stored-value-banner.jpg', '已支付储值订单同样要有封面图');

console.log('储值订单封面图与未支付态测试通过');
