import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

globalThis.wx = {
  getStorageSync() { return ''; },
  setStorageSync() {},
  removeStorageSync() {}
};
globalThis.getApp = () => ({ globalData: {} });

const { normalizeRemoteProfile } = require(path.join(root, 'utils/user-profile.js'));

// 1. totalSpend / growth 必须按「分 -> 元」换算（成长值 = 累计消费，1 元 = 1 成长值）
const withSpend = normalizeRemoteProfile({ totalSpend: 25000, balance: 10000, points: 30 });
assert.equal(withSpend.totalSpend, 250, 'totalSpend 必须从分换算为元（25000 分 -> 250 元）');
assert.equal(withSpend.growth, 250, 'growth 必须与 totalSpend 同值（成长值 = 累计消费）');

// 2. 后端未下发 totalSpend 时兜底为 0（历史兼容，不得 NaN）
const withoutSpend = normalizeRemoteProfile({ balance: 10000, points: 30 });
assert.equal(withoutSpend.totalSpend, 0, '缺少 totalSpend 必须兜底为 0');
assert.equal(withoutSpend.growth, 0, '缺少 totalSpend 时 growth 必须兜底为 0');

// 3. balance 仍按分转元，points 原样（不破坏既有行为）
assert.equal(withSpend.balance, 100, 'balance 仍按分转元');
assert.equal(withSpend.points, 30, 'points 原样保留');

console.log('用户资料成长值（totalSpend/growth）换算测试通过');
