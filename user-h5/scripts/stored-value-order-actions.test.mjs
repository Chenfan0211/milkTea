import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 储值订单取消与立即支付接入回归。
 */

globalThis.getApp = () => ({ globalData: {} });
globalThis.wx = {
  showToast() {}, showModal() {}, navigateTo() {}, navigateBack() {},
  showShareMenu() {}, showLoading() {}, hideLoading() {}
};

const apiJs = fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8');
assert.ok(apiJs.includes('cancelStoredValueOrder'), 'api.js 必须提供 cancelStoredValueOrder');

const ordersJs = fs.readFileSync(path.join(root, 'utils/orders.js'), 'utf8');
assert.ok(
  ordersJs.includes('stored-value') && ordersJs.includes('cancelOrderById'),
  '订单数据层必须支持储值订单取消分流'
);

// 页面 handlePay 必须真实接入支付（不再只是占位 toast）
const ordersPageJs = fs.readFileSync(path.join(root, 'pages/orders/orders.js'), 'utf8');
assert.ok(ordersPageJs.includes('prepayStoredValue'), '立即支付必须调用储值预支付');
assert.ok(ordersPageJs.includes('fetchStoredValueOrder'), '支付后必须查单确认');

// 支付方式展示：已支付订单不得被 payStatus=PAID 反推成「微信支付」
const orders = require(path.join(root, 'utils/orders.js'));
orders.setOrdersForTest([
  {
    id: 'sv-display-1',
    category: 'store',
    timeGroup: 'today',
    orderStatus: 'pending_verify',
    status: '待核销',
    payStatus: 'PAID',
    payChannel: 'STORED_VALUE',
    items: [],
    orderInfo: { orderNo: 'WX-STORED-1', createdAt: '2026-09-28 03:41:13' }
  }
]);
assert.equal(
  orders.getOrderById('sv-display-1').payMethodText,
  '储值余额',
  '储值余额支付的订单必须显示「储值余额」，不得按支付状态显示微信支付'
);

console.log('储值订单取消与立即支付接入测试通过');
