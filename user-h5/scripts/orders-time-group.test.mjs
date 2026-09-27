import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 今日 / 历史订单归属回归测试。
 *
 * 背景：订单页「今日订单」必须只展示今天创建的订单，不得混入其他日期。
 * 旧实现把「按今日页签请求到的整批数据」直接标成 today，导致昨天订单也进入今日列表。
 */

globalThis.getApp = () => ({ globalData: {} });
globalThis.wx = {
  request() {
    return undefined;
  },
  showToast() {},
  showModal() {},
  navigateTo() {},
  navigateBack() {},
  showShareMenu() {}
};

const orders = require(path.join(root, 'utils/orders.js'));

// 1. resolveTimeGroup 必须按本地日历日判断
assert.equal(typeof orders.resolveTimeGroup, 'function', '必须导出 resolveTimeGroup');

const NOW_DAY = new Date('2026-09-27T12:00:00').getTime();

// 昨天订单即使出现在“today”请求中，也必须归为 history
assert.equal(orders.resolveTimeGroup('2026-09-26 23:59:59', NOW_DAY), 'history', '昨天订单必须归为历史');
assert.equal(orders.resolveTimeGroup('2026-09-27 00:00:01', NOW_DAY), 'today', '今天订单必须归为今日');
assert.equal(orders.resolveTimeGroup('2026-09-27 23:59:59', NOW_DAY), 'today', '今天深夜订单仍为今日');
assert.equal(orders.resolveTimeGroup('2026-09-28 00:00:01', NOW_DAY), 'history', '明天订单应为历史（防御）');
assert.equal(orders.resolveTimeGroup(null, NOW_DAY), 'history', '无法解析时间必须兜底为历史');
assert.equal(orders.resolveTimeGroup('not-a-date', NOW_DAY), 'history', '非法时间必须兜底为历史');

console.log('今日/历史订单归属（resolveTimeGroup）测试通过');
