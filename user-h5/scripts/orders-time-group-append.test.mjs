import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * refreshOrdersFromRemote 分页与跨天归属回归。
 */

globalThis.getApp = () => ({ globalData: {} });

const page1 = {
  records: [
    { id: 1, orderNo: 'A1', category: 'store', status: 'PAID', payStatus: 'PAID', totalAmount: 1000, createTime: '2026-09-26 10:00:00', items: [{ productId: 'p', name: '昨天饮品', unitPrice: 1000, quantity: 1 }] },
    { id: 2, orderNo: 'A2', category: 'store', status: 'PAID', payStatus: 'PAID', totalAmount: 1000, createTime: '2026-09-27 10:00:00', items: [{ productId: 'p', name: '今天饮品', unitPrice: 1000, quantity: 1 }] }
  ],
  total: 3
};
const page2 = {
  records: [
    { id: 3, orderNo: 'A3', category: 'store', status: 'PAID', payStatus: 'PAID', totalAmount: 1000, createTime: '2026-09-27 11:00:00', items: [{ productId: 'p', name: '今天第二杯', unitPrice: 1000, quantity: 1 }] }
  ],
  total: 3
};

globalThis.wx = {
  request(options) {
    const data = options.data || {};
    const isOrders = /\/api\/v1\/app\/orders(\?|$)/.test(options.url);
    const resp = isOrders ? (Number(data.page) >= 2 ? page2 : page1) : { records: [], total: 0 };
    // request 内部 success 读取 res.data 作为 body，unwrap 取 body.data
    options.success({ statusCode: 200, data: { code: 200, data: resp } });
  },
  showToast() {},
  showModal() {},
  navigateTo() {},
  navigateBack() {},
  showShareMenu() {}
};

const orders = require(path.join(root, 'utils/orders.js'));
const NOW = new Date('2026-09-27T12:00:00').getTime();

// 首屏：请求 today 页签，昨天订单必须被归为 history
orders.resetOrders();
await orders.refreshOrdersFromRemote({ page: 1, timeGroup: 'today', category: 'all' });
let list = orders.getOrders(NOW);
const today = list.filter(o => o.timeGroup === 'today').map(o => o.id);
const history = list.filter(o => o.timeGroup === 'history').map(o => o.id);
assert.deepEqual(today.map(String).sort(), ['2'], '今日列表必须只含今天订单（id=2）');
assert.deepEqual(history.map(String).sort(), ['1'], '昨天订单必须归入历史');

// 翻页 append：追加第二页，首页数据不得丢失
await orders.refreshOrdersFromRemote({ page: 2, append: true, timeGroup: 'today', category: 'all' });
list = orders.getOrders(NOW);
assert.deepEqual(list.map(o => String(o.id)).sort(), ['1', '2', '3'], 'append 后必须保留首页与新增页数据');

console.log('refreshOrdersFromRemote 跨天归属与分页 append 测试通过');
