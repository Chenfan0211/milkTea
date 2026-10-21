import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 用户级本地缓存隔离回归（真实反馈：「优惠券和用户有绑定关系，每个用户的券数据要做隔离」）。
 *
 * 缺口：券/资料/经营角色/收藏等缓存的 key 不带用户维度，
 * 而 clearSession() 原先只清 token 与 auth:user ——
 * 同一台设备换账号后，新用户会先看到上一个用户的券张数、时光币/余额、经营角色等。
 *
 * 修复口径：
 *   ① 登出（clearSession）清空全部用户级缓存；
 *   ② saveSession 发现 userId 变化（或旧身份未知）时先清用户级缓存；
 *   ③ 同一用户重复登录不清（保住缓存，避免每次登录都白跑接口）；
 *   ④ 设备级缓存（定位/门店偏好/门店类型）不受影响。
 */

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
  request() {},
  login() {}
};
globalThis.getApp = () => ({ globalData: {} });

const auth = require(path.join(root, 'utils/auth.js'));

const USER_SCOPED_KEYS = auth.USER_SCOPED_KEYS;
assert.ok(Array.isArray(USER_SCOPED_KEYS) && USER_SCOPED_KEYS.length > 0, 'auth 必须导出用户级缓存清单');

const DEVICE_SCOPED_KEYS = ['milkTea:location-context', 'milkTea:store-preference', 'milkTea:store-types:cache'];

function seedUserData() {
  wx.setStorageSync('milkTea:coupons', [{ id: '1' }]);
  wx.setStorageSync('milkTea:coupons:usable-count', 1);
  wx.setStorageSync('milkTea:user-profile', { points: 61 });
  wx.setStorageSync('milkTea:tongue-profile', { gender: 'female', birthYear: '1991' });
  wx.setStorageSync('milkTea:recommendation-carts', { ownerId: '11', carts: { r1: { items: [] } } });
  wx.setStorageSync('milkTea:business-role', 'STORE');
  wx.setStorageSync('milkTea:favorite-store-ids', [7]);
  // 设备级：与账号无关，不应被清
  DEVICE_SCOPED_KEYS.forEach(key => wx.setStorageSync(key, { keep: true }));
}

function snapshot(keys) {
  return keys.map(key => storage[key]);
}

// ---------- 用例 1：用户 A 登录后写入缓存 ----------
auth.saveSession({ token: 'token-A', userId: 11 });
seedUserData();
assert.equal(auth.getUserId(), '11', 'saveSession 必须记录当前用户 id');
assert.deepEqual(storage['milkTea:coupons'], [{ id: '1' }], 'A 的券缓存应写入');

// ---------- 用例 2：同一用户重复登录不得清缓存 ----------
const before = snapshot([
  'milkTea:coupons',
  'milkTea:coupons:usable-count',
  'milkTea:user-profile',
  'milkTea:tongue-profile'
]);
auth.saveSession({ token: 'token-A2', userId: 11 });
assert.deepEqual(
  snapshot(['milkTea:coupons', 'milkTea:coupons:usable-count', 'milkTea:user-profile', 'milkTea:tongue-profile']),
  before,
  '同一用户重复登录不应清空用户级缓存'
);

// ---------- 用例 3：换账号（不同 userId）必须清掉上一个用户的缓存 ----------
auth.saveSession({ token: 'token-B', userId: 22 });
assert.equal(auth.getUserId(), '22', 'userId 必须更新为新账号');
USER_SCOPED_KEYS.forEach(key => {
  assert.equal(storage[key], undefined, `换账号后用户级缓存 ${key} 必须清空（用户数据隔离）`);
});
DEVICE_SCOPED_KEYS.forEach(key => {
  assert.deepEqual(storage[key], { keep: true }, `设备级缓存 ${key} 不应被清`);
});
assert.equal(storage['milkTea:auth:token'], 'token-B', 'token 必须切换为新账号');

// ---------- 用例 4：登出必须清 token + 全部用户级缓存 ----------
seedUserData();
auth.clearSession();
assert.equal(storage['milkTea:auth:token'], undefined, '登出必须清 token');
assert.equal(auth.getUserId(), null, '登出必须清 user id');
USER_SCOPED_KEYS.forEach(key => {
  assert.equal(storage[key], undefined, `登出后用户级缓存 ${key} 必须清空`);
});
DEVICE_SCOPED_KEYS.forEach(key => {
  assert.deepEqual(storage[key], { keep: true }, `登出不应影响设备级缓存 ${key}`);
});

// ---------- 用例 5：券张数缓存也在隔离清单里 ----------
assert.ok(USER_SCOPED_KEYS.includes('milkTea:coupons'), '券包缓存必须按用户隔离');
assert.ok(USER_SCOPED_KEYS.includes('milkTea:tongue-profile'), '舌诊资料缓存必须按用户隔离');
assert.ok(USER_SCOPED_KEYS.includes('milkTea:recommendation-carts'), '推荐购物车缓存必须按用户隔离');
assert.ok(USER_SCOPED_KEYS.includes('milkTea:coupons:usable-count'), '券张数缓存必须按用户隔离');

console.log('用户级本地缓存隔离（登出/换账号不串数据）测试通过');
