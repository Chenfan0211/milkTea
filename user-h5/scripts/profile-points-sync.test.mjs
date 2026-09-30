import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 「我的」页时光币与真实余额一致性回归。
 *
 * 真实故障：签到 / 兑换后进「时光币商城」看到的是新余额，
 * 退回「我的」页仍显示旧值（截图：商城与卡片对不上）。
 *
 * 根因两层：
 *   1. profile.onShow 先用本地缓存渲染资产卡，fetchMe 远端返回后
 *      **没有重算 stats**，卡片永远停在 onShow 那一刻的快照；
 *   2. auth.fetchMe() 只写自己的 milkTea:auth:user，不写
 *      milkTea:user-profile，而 getPoints() 读的是后者 —— 两个副本。
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
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 })
};

// 后端 app_user.points 的真实值：25（本地缓存里是旧的 13）
const REMOTE_POINTS = 25;
globalThis.wx.request = function request(options) {
  setTimeout(() => {
    options.success &&
      options.success({
        statusCode: 200,
        data: { code: 0, message: 'ok', data: { points: REMOTE_POINTS, balance: 0, totalSpend: 0 } }
      });
  }, 10);
};

const published = [];
const appObj = {
  globalData: { points: 0, signedDates: [], pointsListeners: [] },
  subscribePoints(listener) {
    this.globalData.pointsListeners.push(listener);
    return () => {
      this.globalData.pointsListeners = this.globalData.pointsListeners.filter(i => i !== listener);
    };
  },
  publishPointsChanged(points, source) {
    published.push({ points, source });
    this.globalData.points = points;
    (this.globalData.pointsListeners || []).slice().forEach(listener => {
      try {
        listener(points, source);
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

const auth = require(path.join(root, 'utils/auth.js'));
const profile = require(path.join(root, 'utils/user-profile.js'));
const { getPoints, notifyPointsChanged } = require(path.join(root, 'utils/points.js'));

// 登录态 + 旧缓存 13
wx.setStorageSync('milkTea:auth:token', 'test-token');
auth.saveSession({ token: 'test-token' });
profile.saveUserProfile(Object.assign({}, profile.getUserProfile(), { points: 13 }));

function createPage() {
  const instance = Object.assign({}, pageDefinition);
  instance.data = JSON.parse(JSON.stringify(pageDefinition.data));
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}

// ---------- 用例 1：onShow 后远端返回，「我的」页必须显示真实余额 ----------
require(path.join(root, 'pages/profile/profile.js'));
const page = createPage();
if (typeof page.onLoad === 'function') page.onLoad();
page.onShow();

const pointsAtSync = page.data.stats.find(item => item.id === 'points').value;
assert.equal(pointsAtSync, 13, 'onShow 同步阶段先用本地缓存渲染（秒出）');

await new Promise(resolve => setTimeout(resolve, 250));

const pointsAfterRemote = page.data.stats.find(item => item.id === 'points').value;
assert.equal(
  pointsAfterRemote,
  REMOTE_POINTS,
  '远端资料返回后，「我的」页时光币必须刷新为服务端真实值（不得停留在本地旧值）'
);
assert.equal(appObj.globalData.points, REMOTE_POINTS, '远端返回后 globalData.points 必须同步为真实值，供其它页面读取');
assert.equal(getPoints(), REMOTE_POINTS, 'getPoints() 必须返回服务端真实值，不得被旧缓存覆盖');

// ---------- 用例 2：广播能让订阅页面同步（我的页 + 其它页共用一条链路） ----------
const received = [];
const unsubscribe = appObj.subscribePoints(points => received.push(points));
notifyPointsChanged(31, { source: 'signin' });
assert.deepEqual(received, [31], '签到等变更必须广播给订阅页面');
assert.equal(appObj.globalData.points, 31, '广播后会话镜像必须更新');
assert.equal(getPoints(), 31, '广播后持久缓存必须更新');
assert.ok(
  published.some(item => item.points === 31 && item.source === 'signin'),
  '广播必须携带来源，便于排查是谁改的余额'
);
unsubscribe();

// ---------- 用例 3：商城页余额必须跟随广播 ----------
const mallDefinition = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.js'), 'utf8');
assert.ok(mallDefinition.includes('subscribePoints'), '时光币商城必须订阅时光币广播，否则跨页余额不一致');
assert.ok(mallDefinition.includes('notifyPointsChanged'), '时光币商城远端刷新后必须广播余额');

// ---------- 用例 4：源码契约（防止再次漏掉远端回填） ----------
const profileSource = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(profileSource.includes('refreshAssetStats'), '「我的」页必须提供统一的资产卡重算方法');
const fetchMeBlock = profileSource.slice(profileSource.indexOf('fetchMe(true)'));
assert.ok(
  /fetchMe\(true\)[\s\S]{0,2500}?refreshAssetStats\(\)/.test(fetchMeBlock),
  'fetchMe 成功后必须调用 refreshAssetStats()，否则资产卡会停留在本地旧值'
);

const signinSource = fs.readFileSync(path.join(root, 'pages/points-signin/points-signin.js'), 'utf8');
assert.ok(signinSource.includes('notifyPointsChanged'), '签到发放时光币后必须广播，否则其它页面显示旧余额');

const exchangeSource = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.js'), 'utf8');
assert.ok(exchangeSource.includes('notifyPointsChanged'), '兑换扣减时光币后必须回写并广播（历史缺口：完全没回写）');

console.log('「我的」页时光币一致性与广播测试通过');
