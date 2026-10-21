import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 优惠券四态口径回归（对应后端 CouponService#deriveTab）。
 *
 * 真实反馈：「优惠券过期了，却在「已使用」里面」。排查结论分两层：
 *   1. 已使用是终态，用过的券之后过了有效期仍留在「已使用」是正常的 ——
 *      问题在展示：卡片还写「xx 到期」（一个过去的日期），看起来像过期券被分错 Tab；
 *   2. 前端 `expired: usable === false` 把「不可用」当成了「已过期」——
 *      已使用/订单占用中的券同样 usable=false，于是全被标成已过期。
 *      「过期」必须由服务端 UserCouponView.expired 给出。
 *
 * 另外守护两条口径：
 *   · 订单占用中（LOCKED）的券显示在「未使用」并标注「订单占用中」，但不可点「去使用」；
 *   · 待生效（PENDING）不计入「优惠券 N 张」，也不能出现「去使用」。
 */

// ---------- 小程序运行时桩 ----------
const storage = {};
globalThis.wx = {
  getStorageSync: key => (storage[key] === undefined ? '' : storage[key]),
  setStorageSync: (key, value) => {
    storage[key] = value;
  },
  removeStorageSync: key => {
    delete storage[key];
  },
  showShareMenu() {},
  showToast() {},
  navigateTo() {},
  switchTab() {}
};
globalThis.getApp = () => ({ globalData: {} });

let pageDefinition = null;
globalThis.Page = definition => {
  pageDefinition = definition;
};

const api = require(path.join(root, 'utils/api.js'));
const coupons = require(path.join(root, 'utils/coupons.js'));

// 后端四态响应（字段与 marketing-service UserCouponView 对齐）
const PAST = '2026-10-01 10:00:00';
const FUTURE = '2099-12-31 23:59:59';
const BY_TAB = {
  UNUSED: [
    { id: 1, status: 'UNUSED', usable: true, expired: false, expireAt: FUTURE },
    // 订单占用中：仍在「未使用」展示，但不可用
    { id: 2, status: 'LOCKED', usable: false, expired: false, expireAt: FUTURE }
  ],
  PENDING: [{ id: 3, status: 'UNUSED', usable: false, expired: false, expireAt: FUTURE }],
  EXPIRED: [{ id: 4, status: 'UNUSED', usable: false, expired: true, expireAt: PAST }],
  // 已使用且已过有效期：终态优先，expired 必须为 false（它属于「已使用」）
  USED: [{ id: 5, status: 'USED', usable: false, expired: false, expireAt: PAST }]
};
api.fetchUserCoupons = status => Promise.resolve(BY_TAB[status || 'UNUSED']);

require(path.join(root, 'pages/coupon-list/coupon-list.js'));

// ---------- 1. expired 必须来自服务端，不能用 usable 推断 ----------
const usedNormalized = coupons.normalizeRemoteCoupon(BY_TAB.USED[0]);
assert.equal(usedNormalized.usable, false, '已使用券不可用');
assert.equal(usedNormalized.expired, false, '已使用券不得被当成已过期（usable=false ≠ 已过期）');

const lockedNormalized = coupons.normalizeRemoteCoupon(BY_TAB.UNUSED[1]);
assert.equal(lockedNormalized.locked, true, 'LOCKED 券必须能识别为「订单占用中」');
assert.equal(lockedNormalized.expired, false, '订单占用中不等于已过期');

const expiredNormalized = coupons.normalizeRemoteCoupon(BY_TAB.EXPIRED[0]);
assert.equal(expiredNormalized.expired, true, '服务端标 expired 的券必须为已过期');

// ---------- 2. 券张数：只数可用券，且不被其它 Tab 污染 ----------
await coupons.refreshCouponsFromRemote('UNUSED');
assert.equal(coupons.getUsableCouponCount(), 1, '只剩 1 张真正可用的券（订单占用中的不计入）');

await coupons.refreshCouponsFromRemote('PENDING');
assert.equal(coupons.getUsableCouponCount(), 1, '待生效券不计入「优惠券 N 张」');

await coupons.refreshCouponsFromRemote('USED');
assert.equal(coupons.getUsableCouponCount(), 1, '逛过「已使用」Tab 后张数不得变成 0（券包缓存被覆盖是历史根因）');

// ---------- 3. 卡片展示态 ----------
assert.ok(pageDefinition && typeof pageDefinition.decorateCoupon === 'function', '页面必须提供 decorateCoupon');

const lockedCard = pageDefinition.decorateCoupon(coupons.normalizeRemoteCoupon(BY_TAB.UNUSED[1]), 'UNUSED');
assert.equal(lockedCard.usable, false, '订单占用中的券不可点「去使用」');
assert.equal(lockedCard.statusText, '订单占用中', '订单占用中的券必须标注「订单占用中」');
assert.equal(lockedCard.disabled, false, '订单占用中的券仍属于「未使用」区，不能整体置灰');

const availableCard = pageDefinition.decorateCoupon(coupons.normalizeRemoteCoupon(BY_TAB.UNUSED[0]), 'UNUSED');
assert.equal(availableCard.usable, true, '可用券必须能点「去使用」');

const pendingCard = pageDefinition.decorateCoupon(coupons.normalizeRemoteCoupon(BY_TAB.PENDING[0]), 'PENDING');
assert.equal(pendingCard.usable, false, '待生效券不得出现「去使用」');
assert.equal(pendingCard.statusText, '待生效', '待生效券必须标注「待生效」');

const usedCard = pageDefinition.decorateCoupon(coupons.normalizeRemoteCoupon(BY_TAB.USED[0]), 'USED');
assert.equal(usedCard.statusText, '已使用', '已使用券的状态文案必须是「已使用」');
assert.ok(
  usedCard.expiryText.includes('已于') && !usedCard.expiryText.includes('到期'),
  '已使用券的到期时间已经过去，文案必须写成「已于 xx 过期」，不能写成「xx 到期」'
);

const expiredCard = pageDefinition.decorateCoupon(coupons.normalizeRemoteCoupon(BY_TAB.EXPIRED[0]), 'EXPIRED');
assert.equal(expiredCard.statusText, '已过期', '已过期券必须标注「已过期」');

console.log('优惠券四态口径（过期/已使用/订单占用/待生效）测试通过');
