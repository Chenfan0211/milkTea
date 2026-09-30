import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 订单详情参数契约回归。
 *
 * 背景（真实故障）：确认页支付成功后用 ?orderNo= 跳详情页，
 * 而详情页 onLoad 只读 options.id，导致 orderId 恒为空串，
 * 详情页提示「订单不存在」。这里锁死两端契约，防止再次错配。
 */

const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
const detailJs = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.js'), 'utf8');

// 确认页跳转必须携带 orderNo
assert.ok(confirmJs.includes('pay-success/pay-success?orderNo='), '支付成功后必须跳支付成功页并携带 orderNo');

// 详情页必须同时解析 orderNo 与 id 两种入口
assert.ok(
  /orderId\s*=\s*opts\.id\s*\|\|\s*opts\.orderNo/.test(detailJs),
  '详情页 onLoad 必须兼容 opts.id 与 opts.orderNo'
);

console.log('订单详情参数契约测试通过');
