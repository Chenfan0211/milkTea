import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 支付成功后清空购物车回归。
 *
 * 背景（真实故障）：从购物车去结算，支付完成后购物车商品未清空。
 * 这里验证：
 *   1) removeCartItems 只移除已结算条目、保留未选中商品；
 *   2) 点单页标记了 fromCart（结算）/ false（立即购买）；
 *   3) 确认页仅在 fromCart 时写 settledCartIds。
 */

const { removeCartItems } = require(path.join(root, 'utils/cart.js'));

// 1) 移除已结算条目，保留其余
const cart = [
  { id: 'a', name: 'A' },
  { id: 'b', name: 'B' },
  { id: 'c', name: 'C' }
];
const after = removeCartItems(cart, ['a', 'c']);
assert.deepEqual(
  after.map(i => i.id),
  ['b'],
  'removeCartItems 必须只移除已结算条目'
);
assert.deepEqual(
  cart.map(i => i.id),
  ['a', 'b', 'c'],
  'removeCartItems 不得修改原数组'
);

// 2) 空 settledIds 不改变内容
const unchanged = removeCartItems(cart, []);
assert.deepEqual(
  unchanged.map(i => i.id),
  ['a', 'b', 'c'],
  '空 settledIds 不得移除任何条目'
);

// 3) settledIds 为 null/undefined 不报错
assert.deepEqual(
  removeCartItems(cart, null).map(i => i.id),
  ['a', 'b', 'c'],
  'null settledIds 不得报错'
);

// 4) 源码契约：menu.js 结算/立即购买必须显式标记 fromCart
const menuJs = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
assert.ok(/fromCart:\s*true/.test(menuJs), '购物车结算必须标记 fromCart: true');
assert.ok(/fromCart:\s*false/.test(menuJs), '立即购买必须标记 fromCart: false');

// 5) 确认页仅在 fromCart 时写 settledCartIds
const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
assert.ok(/markSettledCart\(\)\s*\{[\s\S]*?settledFromCart/.test(confirmJs), '确认页必须仅在购物车来源时写入结算标记');
assert.ok(
  confirmJs.includes('app.globalData.settledCartIds = this.settledCartIds'),
  '确认页必须写 settledCartIds 到 globalData'
);

console.log('支付后清空购物车测试通过');
