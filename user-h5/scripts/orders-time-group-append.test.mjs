import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * refreshOrdersFromRemote 时间范围传参与分页 append 回归。
 *
 * 语义（新）：门店订单「今日 / 历史」页签按自然日口径，由后端 createTime 精确过滤。
 * 前端不再整批拉回再本地打标，而是把 startTime/endTime 传给订单接口，
 * 从源头避免跨午夜「今日订单」看不到昨晚订单。
 */

globalThis.getApp = () => ({ globalData: {} });

const page1 = {
  records: [
    {
      id: 1,
      orderNo: 'A1',
      category: 'store',
      status: 'PAID',
      payStatus: 'PAID',
      totalAmount: 1000,
      createTime: '2026-09-26 10:00:00',
      items: [{ productId: 'p', name: '昨天饮品', unitPrice: 1000, quantity: 1 }]
    },
    {
      id: 2,
      orderNo: 'A2',
      category: 'store',
      status: 'PAID',
      payStatus: 'PAID',
      totalAmount: 1000,
      createTime: '2026-09-27 10:00:00',
      items: [{ productId: 'p', name: '今天饮品', unitPrice: 1000, quantity: 1 }]
    }
  ],
  total: 3
};
const page2 = {
  records: [
    {
      id: 3,
      orderNo: 'A3',
      category: 'store',
      status: 'PAID',
      payStatus: 'PAID',
      totalAmount: 1000,
      createTime: '2026-09-27 11:00:00',
      items: [{ productId: 'p', name: '今天第二杯', unitPrice: 1000, quantity: 1 }]
    }
  ],
  total: 3
};

// 记录订单接口收到的查询参数，供断言验证
const capturedQueries = [];

globalThis.wx = {
  request(options) {
    const data = options.data || {};
    const isOrders = /\/api\/v1\/app\/orders(\?|$)/.test(options.url);
    if (isOrders) capturedQueries.push(data);
    const resp = isOrders ? (Number(data.page) >= 2 ? page2 : page1) : { records: [], total: 0 };
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

// 首屏：请求 today 页签，必须携带今天 00:00 到明天 00:00 的时间范围
orders.resetOrders();
await orders.refreshOrdersFromRemote({ page: 1, timeGroup: 'today', category: 'all' });
let list = orders.getOrders(NOW);
assert.ok(capturedQueries.length >= 1, '必须发起订单接口请求');
const todayQuery = capturedQueries[0];
assert.ok(todayQuery.startTime, '今日订单请求必须携带 startTime');
assert.ok(todayQuery.endTime, '今日订单请求必须携带 endTime');
assert.match(todayQuery.startTime, /^\d{4}-\d{2}-\d{2} 00:00:00$/, 'startTime 必须为当日 00:00:00');
assert.match(todayQuery.endTime, /^\d{4}-\d{2}-\d{2} 00:00:00$/, 'endTime 必须为次日 00:00:00');
assert.ok(todayQuery.startTime < todayQuery.endTime, 'startTime 必须早于 endTime');

// 翻页 append：追加第二页，仍携带同样的时间范围
await orders.refreshOrdersFromRemote({ page: 2, append: true, timeGroup: 'today', category: 'all' });
list = orders.getOrders(NOW);
assert.equal(capturedQueries.length, 2, '翻页必须再次发起带时间范围的订单请求');
const page2Query = capturedQueries[1];
assert.equal(page2Query.page, 2, '翻页请求必须携带 page=2');
assert.ok(page2Query.startTime && page2Query.endTime, '翻页请求同样必须携带时间范围');

// 历史订单页签：只携带 endTime（今天 00:00:00），不带 startTime
await orders.refreshOrdersFromRemote({ page: 1, timeGroup: 'history', category: 'all' });
const historyQuery = capturedQueries[capturedQueries.length - 1];
assert.equal(historyQuery.startTime, undefined, '历史订单请求不得携带 startTime');
assert.ok(historyQuery.endTime, '历史订单请求必须携带 endTime（今天 00:00:00）');

console.log('refreshOrdersFromRemote 时间范围传参与分页 append 测试通过');
