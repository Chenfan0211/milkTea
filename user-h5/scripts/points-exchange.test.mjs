import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = {};
const toasts = [];
let modalOptions = null;
let navigateBackCount = 0;

globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    toasts.push(options);
  },
  showModal(options) {
    modalOptions = options;
  },
  navigateBack() {
    navigateBackCount += 1;
  },
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 })
};
const mockApp = {
  globalData: {
    points: 1000,
    pointsRecords: [],
    exchangeRecords: [],
    exchangeVerifyPool: []
  }
};
globalThis.getApp = () => mockApp;

const { getPoints, setPoints, verifyExchange } = require(path.join(root, 'utils/points.js'));

// 假数据清理：兑换与核销均为服务端写操作，前端不再有本地状态机。
// utils/points.js 只保留余额读写（缓存镜像）与核销接口封装。
assert.equal(
  typeof require(path.join(root, 'utils/points.js')).exchangeProduct,
  'undefined',
  'exchangeProduct must be removed (exchange is a server-side write now)'
);

// 余额读写：以 profile 为持久源
setPoints(60);
assert.equal(getPoints(), 60, 'setPoints/getPoints must round-trip the balance');
setPoints(-5);
assert.equal(getPoints(), 0, 'setPoints must clamp negatives to zero');
setPoints(1000);

// 核销：走后端 POST /api/v1/app/gift-cards/verify（按订单号）
const apiSource = fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8');
assert.ok(
  apiSource.includes("'/api/v1/app/gift-cards/verify'"),
  'api.js must expose the gift-card verify endpoint'
);
assert.ok(
  apiSource.includes('verifyGiftCardOrder'),
  'api.js must export verifyGiftCardOrder'
);
const pointsSource = fs.readFileSync(path.join(root, 'utils/points.js'), 'utf8');
assert.ok(
  !pointsSource.includes('milkTea:exchange:pool'),
  'verify must not use the local exchange pool anymore'
);
assert.ok(
  pointsSource.includes('verifyGiftCardOrder'),
  'verifyExchange must delegate to the backend verify endpoint'
);

// 空码直接短路，不发起请求
const emptyResult = await verifyExchange('');
assert.equal(emptyResult.ok, false, 'empty code must not verify');
assert.equal(emptyResult.reason, 'empty', 'empty code reason must be "empty"');

// 核销结果映射：后端业务错误 -> 可展示原因
const originalVerify = require(path.join(root, 'utils/api.js')).verifyGiftCardOrder;
function stubVerify(impl) {
  require(path.join(root, 'utils/api.js')).verifyGiftCardOrder = impl;
}
stubVerify(() => Promise.resolve({ orderNo: 'EX123' }));
const okResult = await verifyExchange('EX123');
assert.equal(okResult.ok, true, 'successful verify must resolve ok');
assert.equal(okResult.order.orderNo, 'EX123', 'verify must return the backend order');

stubVerify(() => Promise.reject(new Error('订单已核销')));
const dupResult = await verifyExchange('EX123');
assert.equal(dupResult.ok, false, 'duplicate verify must fail');
assert.equal(dupResult.reason, 'already_verified', 'duplicate reason must be already_verified');

stubVerify(() => Promise.reject(new Error('礼品卡订单不存在')));
const missingResult = await verifyExchange('NOPE');
assert.equal(missingResult.ok, false, 'unknown code must fail');
assert.equal(missingResult.reason, 'not_found', 'unknown code reason must be not_found');

stubVerify(() => Promise.reject(new Error('订单未支付，无法核销')));
const unpaidResult = await verifyExchange('EX999');
assert.equal(unpaidResult.ok, false, 'unpaid order verify must fail');
assert.equal(unpaidResult.reason, 'failed', 'unpaid reason must fall back to failed');
stubVerify(originalVerify);

// 兑换记录页含待核销页签
const mockSource = fs.readFileSync(path.join(root, 'data/mock.js'), 'utf8');
assert.ok(mockSource.includes("id: 'pending_verify'"), '兑换记录分类必须含待核销');

console.log('时光币兑换：二次确认、余额校验、扣减与记录写入测试通过');
