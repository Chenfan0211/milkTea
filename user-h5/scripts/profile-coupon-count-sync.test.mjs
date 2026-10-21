import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 「我的」页优惠券张数一致性与渲染竞争回归。
 *
 * 真实故障（截图：我的券包里有 1 张可用券，「我的」页优惠券显示 0）：
 * 进页面时看到 1，从「储值余额」返回后变成 0。
 *
 * 根因两层：
 *   1. utils/user-profile 的资料缓存里 couponCount 被写死为 0，
 *      而 /auth/me 本身不返回券数量；
 *   2. profile.onShow 并发发起 fetchMe(true) 与 refreshCouponsFromRemote('UNUSED')，
 *      fetchMe 成功后回调 refreshAssetStats() 用缓存的 0 覆盖掉券包刚取到的张数 ——
 *      两条请求谁后返回谁说了算，于是同一个页面时而 1、时而 0。
 *
 * 修复后：优惠券张数唯一数据源是券包缓存（utils/coupons），
 * 与首页「优惠券N张」、券包「未使用」Tab 口径一致。
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
  showToast() {},
  showModal() {},
  navigateTo() {},
  showShareMenu() {},
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 }),
  // 经营角色 / 会员等级 / 我的页功能配置等旁路请求：返回空壳即可，
  // 本用例只关心资产卡里的优惠券张数。
  request(options) {
    setTimeout(() => {
      options.success && options.success({ statusCode: 200, data: { code: 0, message: 'ok', data: {} } });
    }, 5);
  }
};

const appObj = {
  globalData: { points: 0, signedDates: [], pointsListeners: [] },
  subscribePoints(listener) {
    this.globalData.pointsListeners.push(listener);
    return () => {
      this.globalData.pointsListeners = this.globalData.pointsListeners.filter(i => i !== listener);
    };
  },
  publishPointsChanged(points) {
    this.globalData.points = points;
    (this.globalData.pointsListeners || []).slice().forEach(listener => {
      try {
        listener(points);
      } catch (error) {
        /* 隔离 */
      }
    });
  }
};
globalThis.getApp = () => appObj;

let pageDefinition = null;
globalThis.Page = definition => {
  pageDefinition = definition;
};

const api = require(path.join(root, 'utils/api.js'));
const auth = require(path.join(root, 'utils/auth.js'));
const profile = require(path.join(root, 'utils/user-profile.js'));
const coupons = require(path.join(root, 'utils/coupons.js'));

// 登录态：有 token 才会走 /auth/me 与券包刷新
wx.setStorageSync('milkTea:auth:token', 'test-token');
auth.saveSession({ token: 'test-token' });

// 后端券包（status=UNUSED）返回 1 张可用券，对应券包「未使用」Tab 的 1 张
const REMOTE_COUPONS = [{ id: 9, couponCode: 'C-9', usable: true, expired: false, status: 'UNUSED' }];

/** 模拟上一次进入页面时留下的券包缓存（1 张可用券） */
function seedCouponCache() {
  wx.setStorageSync('milkTea:coupons', [{ id: '9', usable: true, expired: false }]);
}

function createPage() {
  const instance = Object.assign({}, pageDefinition);
  instance.data = JSON.parse(JSON.stringify(pageDefinition.data));
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}

function couponOf(page) {
  return page.data.stats.find(item => item.id === 'coupon').value;
}

require(path.join(root, 'pages/profile/profile.js'));

/**
 * 挂载页面并按指定时序返回远端。
 * meDelay —— /auth/me 的返回耗时；couponDelay —— 券包接口的返回耗时。
 */
async function mount({ meDelay, couponDelay, couponFails = false }) {
  profile.saveUserProfile(Object.assign({}, profile.getUserProfile(), { points: 13 }));
  api.fetchMe = () =>
    new Promise(resolve => setTimeout(() => resolve({ points: 61, balance: 88650, totalSpend: 7900 }), meDelay));
  api.fetchMyGiftCards = () => new Promise(resolve => setTimeout(() => resolve([]), 5));
  api.fetchProfileConfig = () => new Promise(resolve => setTimeout(() => resolve({}), 5));
  api.fetchUserCoupons = () =>
    couponFails
      ? new Promise((resolve, reject) => setTimeout(() => reject(new Error('network down')), couponDelay))
      : new Promise(resolve => setTimeout(() => resolve(REMOTE_COUPONS), couponDelay));

  const page = createPage();
  page.onShow();
  // onShow 同步阶段（等待远端之前）的渲染值
  const firstFrame = couponOf(page);
  await new Promise(resolve => setTimeout(resolve, Math.max(meDelay, couponDelay) + 150));
  return { page, firstFrame };
}

// ---------- 用例 1：原 bug 时序 —— /auth/me 后返回，不得把张数覆盖成 0 ----------
{
  wx.setStorageSync('milkTea:coupons', []);
  const { page } = await mount({ meDelay: 80, couponDelay: 10 });
  assert.equal(
    couponOf(page),
    1,
    '/auth/me 晚于券包返回时，「我的」页优惠券必须仍是券包里的可用券张数（不得被缓存 0 覆盖）'
  );
}

// ---------- 用例 2：反向时序 —— 券包后返回，结果必须一致 ----------
{
  wx.setStorageSync('milkTea:coupons', []);
  const { page } = await mount({ meDelay: 10, couponDelay: 80 });
  assert.equal(couponOf(page), 1, '券包晚于 /auth/me 返回时，「我的」页优惠券同样必须为 1（时序无关）');
  assert.equal(
    couponOf(page),
    coupons.getUsableCouponCount(),
    '「我的」页优惠券张数必须等于券包缓存的可用券张数（同一口径，不允许各算一份）'
  );
}

// ---------- 用例 3：首帧不得闪 0（有缓存时秒出上次已知张数） ----------
{
  seedCouponCache();
  const { page, firstFrame } = await mount({ meDelay: 120, couponDelay: 120 });
  assert.equal(firstFrame, 1, 'onShow 同步阶段必须先按券包缓存渲染出 1，不得先闪 0 再等远端');
  assert.equal(couponOf(page), 1, '远端返回后仍为 1');
}

// ---------- 用例 4：券包接口失败时保留上一次张数，不清零 ----------
{
  seedCouponCache();
  const { page } = await mount({ meDelay: 20, couponDelay: 20, couponFails: true });
  assert.equal(couponOf(page), 1, '券包接口异常时必须保留上一次已知张数，不得显示为 0 张');
}

// ---------- 用例 5：完整复现用户路径（我的 → 储值余额 → 返回我的） ----------
{
  seedCouponCache();
  // 储值页充值成功后会刷新资料缓存（历史 bug：这一次写入把 couponCount 落成 0）
  api.fetchUserProfile = () =>
    new Promise(resolve => setTimeout(() => resolve({ points: 61, balance: 88650, totalSpend: 7900 }), 10));
  await profile.refreshUserProfileFromRemote();
  assert.equal(
    Object.prototype.hasOwnProperty.call(profile.getUserProfile(), 'couponCount'),
    false,
    '资料缓存不得再写入 couponCount 伪字段（否则充值时又会把券张数写成 0）'
  );

  const { page } = await mount({ meDelay: 80, couponDelay: 10 });
  assert.equal(couponOf(page), 1, '从「储值余额」返回「我的」页后，优惠券张数必须仍为 1');
}

// ---------- 用例 6：源码契约，防止伪字段与双写逻辑回归 ----------
{
  /** 契约断言只看代码，不看注释（历史根因会在注释里被引用） */
  const stripComments = source => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

  const profileJs = stripComments(fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8'));
  const userProfileJs = stripComments(fs.readFileSync(path.join(root, 'utils/user-profile.js'), 'utf8'));

  assert.ok(
    !/userProfile\.couponCount|initialProfile\.couponCount/.test(profileJs),
    '「我的」页不得再读取资料缓存里的 couponCount（该字段恒为 0，是这次覆盖 bug 的源头）'
  );
  assert.ok(
    /item\.id === 'coupon'\) return Object\.assign\(\{\}, item, \{ value: getUsableCouponCount\(\) \}\)/.test(
      profileJs
    ),
    '「我的」页优惠券张数必须由 utils/coupons.getUsableCouponCount() 派生'
  );
  assert.ok(
    /require\(['"]\.\.\/\.\.\/utils\/coupons['"]\)/.test(profileJs) && profileJs.includes('refreshCouponsFromRemote'),
    '「我的」页必须复用 utils/coupons 的远端刷新与计数能力'
  );
  assert.ok(!/couponCount:\s*0/.test(userProfileJs), 'utils/user-profile 不得再产出恒为 0 的 couponCount 伪字段');

  const homeJs = fs.readFileSync(path.join(root, 'pages/home/home.js'), 'utf8');
  assert.ok(
    homeJs.includes('getUsableCouponCount'),
    '首页「优惠券N张」必须与「我的」页共用 getUsableCouponCount() 口径'
  );
}

console.log('「我的」页优惠券张数一致性与渲染竞争测试通过');
