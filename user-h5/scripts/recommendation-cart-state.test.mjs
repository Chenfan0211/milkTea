import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = {};
const app = { globalData: {} };

globalThis.getApp = () => app;
globalThis.wx = {
  getStorageSync: key => (storage[key] === undefined ? '' : storage[key]),
  setStorageSync: (key, value) => {
    storage[key] = value;
  },
  removeStorageSync: key => {
    delete storage[key];
  }
};

const auth = require(path.join(root, 'utils/auth.js'));
const statePath = path.join(root, 'utils/recommendation-cart-state.js');
let state = require(statePath);

function item(id, quantity, selected = true) {
  return {
    id,
    productId: id,
    name: id,
    spec: '热饮',
    price: 12.9,
    originalPrice: 15,
    quantity,
    selected,
    listed: true,
    image: id + '.jpg'
  };
}

auth.saveSession({ token: 'token-a', userId: 11 });
assert.deepEqual(state.getItems('report-a'), [], '初始推荐购物车必须为空');

state.mergeItems('report-a', [item('tea-a1', 1)]);
state.mergeItems('report-a', [item('tea-a1', 2)]);
state.mergeItems('report-b', [item('tea-b1', 1)]);
assert.equal(state.getItems('report-a').length, 1, '同一报告同规格必须累加');
assert.equal(state.getItems('report-a')[0].quantity, 3);
assert.deepEqual(
  state.getItems('report-b').map(entry => entry.id),
  ['tea-b1'],
  '不同诊断记录必须互不影响'
);
assert.deepEqual(state.summarizeItems(state.getItems('report-a')), { count: 3, total: 38.7 }, '汇总必须只统计当前报告');

state.setItems(
  'report-a',
  state.getItems('report-a').map(entry => ({ ...entry, selected: false }))
);
assert.deepEqual(state.summarizeItems(state.getItems('report-a')), { count: 0, total: 0 }, '未勾选商品不得计入汇总');

state.markSettled('report-a', ['tea-a1']);
state.consumeSettledItems('report-a');
assert.deepEqual(state.getItems('report-a'), [], '支付成功后必须只清理当前报告的已结算条目');
assert.deepEqual(
  state.getItems('report-b').map(entry => entry.id),
  ['tea-b1'],
  '其他报告购物车不得被结算清理'
);

assert.ok(storage[state.STORAGE_KEY], '推荐购物车必须写入本地缓存');
delete require.cache[require.resolve(statePath)];
state = require(statePath);
assert.deepEqual(
  state.getItems('report-b').map(entry => entry.id),
  ['tea-b1'],
  '重启模块后必须恢复本地购物车'
);

storage[state.STORAGE_KEY] = {
  version: 1,
  ownerId: '11',
  carts: {
    expired: { items: [item('old', 1)], settledIds: [], expiresAt: 1000 },
    active: { items: [item('new', 1)], settledIds: [], expiresAt: 4102444800000 }
  }
};
state.cleanupExpired(2000);
assert.deepEqual(state.getItems('expired'), [], '到达凌晨 1 点必须清理过期报告购物车');
assert.equal(state.getItems('active').length, 1, '未到期报告购物车必须保留');

assert.equal(
  state.nextCleanupAt(new Date(2026, 0, 2, 0, 30).getTime()),
  new Date(2026, 0, 2, 1, 0).getTime(),
  '凌晨 1 点前必须落到当天 1 点'
);
assert.equal(
  state.nextCleanupAt(new Date(2026, 0, 2, 2, 0).getTime()),
  new Date(2026, 0, 3, 1, 0).getTime(),
  '凌晨 1 点后必须落到次日 1 点'
);

auth.saveSession({ token: 'token-b', userId: 22 });
assert.deepEqual(state.getItems('report-b'), [], '切换账号后推荐购物车必须清空');

console.log('recommendation-cart-state.test.mjs: passed');
