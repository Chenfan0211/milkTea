import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pageRoot = path.join(root, 'pages/home/home');

// ---------- 后端优惠券数据 stub（含可用/已过期/已用券） ----------
const couponsStorage = {};
const remoteCoupons = [
  { id: 1, usable: true, expired: false },
  { id: 2, usable: true, expired: false },
  { id: 3, usable: false, expired: true }, // 已过期
  { id: 4, usable: false, expired: false }, // 已使用（不可用）
  { id: 5, usable: true, expired: false }
];

const calls = [];
globalThis.wx = {
  showShareMenu() {},
  getStorageSync(key) {
    return couponsStorage[key] || '';
  },
  setStorageSync(key, value) {
    couponsStorage[key] = value;
    calls.push({ type: 'setStorage', key, value });
  },
  removeStorageSync(key) {
    delete couponsStorage[key];
  },
  showToast(options) {
    calls.push({ type: 'toast', ...options });
  },
  navigateTo(options) {
    calls.push({ type: 'navigateTo', ...options });
  },
  switchTab() {},
  showLoading() {},
  hideLoading() {}
};

// ---------- utils/coupons.getUsableCouponCount ----------
const coupons = require(path.join(root, 'utils/coupons.js'));
assert.ok(
  typeof coupons.getUsableCouponCount === 'function',
  'utils/coupons 必须导出 getUsableCouponCount() 用于首页可用券数量展示'
);

// 写入 mock 缓存（normalizeRemoteCoupon 期望的字段），可用 3 张
couponsStorage['milkTea:coupons'] = remoteCoupons.map(c => ({
  id: String(c.id),
  usable: c.usable,
  expired: c.expired
}));
assert.equal(coupons.getUsableCouponCount(), 3, 'getUsableCouponCount 只统计可用且未过期的券');

// ---------- home.js onShow：登录时拉券并更新 couponCount ----------
let definition;
globalThis.Page = page => {
  definition = page;
};
const api = require(path.join(root, 'utils/api.js'));
// 登录态：fetchMe 成功；fetchUserCoupons 返回上面的远程券
const realFetchMe = api.fetchMe;
const realFetchUserCoupons = api.fetchUserCoupons;
api.fetchMe = () => Promise.resolve({ nickName: '微信用户' });
api.fetchUserCoupons = () => Promise.resolve(remoteCoupons);
// fetchHomeConfig 不需要（保留原实现，但 stub 掉避免报错）
const realFetchHomeConfig = api.fetchHomeConfig;
api.fetchHomeConfig = () => Promise.resolve({});

require(`${pageRoot}.js`);

function createPageInstance() {
  const instance = Object.assign({}, definition);
  instance.data = Object.assign({}, definition.data);
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}

const page = createPageInstance();
assert.ok('couponCount' in page.data, 'home.js data 必须包含 couponCount 字段');

await definition.onShow.call(page);
assert.equal(page.data.couponCount, 3, '登录后首页 couponCount 必须取后端可用券数量');

// ---------- home.js onShow：未登录时 couponCount 为 null（隐藏） ----------
couponsStorage['milkTea:coupons'] = [];
api.fetchMe = () => Promise.reject(new Error('未登录'));
const anonymousPage = createPageInstance();
await definition.onShow.call(anonymousPage);
assert.ok(
  anonymousPage.data.couponCount === null || anonymousPage.data.couponCount === undefined,
  '未登录时 couponCount 必须为 null/undefined（首页隐藏整段优惠券入口）'
);

// ---------- home.wxml：展示 couponCount，且未登录隐藏 ----------
const wxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8');
assert.ok(!wxml.includes('couponCount || 5'), '首页不得再硬编码优惠券数量兜底 5');
assert.ok(wxml.includes('{{couponCount}}张'), '首页优惠券数量必须用 couponCount 渲染');
const couponLinkIndex = wxml.indexOf('class="coupon-link"');
const couponTextIndex = wxml.indexOf('优惠券{{couponCount}}张');
const nicknameIfIndex = wxml.lastIndexOf('wx:if="{{userProfile.nickname}}"', couponTextIndex);
assert.ok(
  nicknameIfIndex > -1 && couponLinkIndex > nicknameIfIndex && couponTextIndex > couponLinkIndex,
  '未登录（无昵称）时优惠券数量入口必须隐藏'
);

// 恢复
api.fetchMe = realFetchMe;
api.fetchUserCoupons = realFetchUserCoupons;
api.fetchHomeConfig = realFetchHomeConfig;

console.log('首页优惠券数量取后端可用券数测试通过');