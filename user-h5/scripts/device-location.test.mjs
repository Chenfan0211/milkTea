import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

/**
 * 设备定位链路测试：授权分支、降级、城市匹配、缓存。
 */

const storage = new Map();
let settingValue = {};
let authorizeBehavior = 'ok';
let getLocationBehavior = 'ok';
let openSettingValue = true;
let modalConfirm = true;
const calls = [];

globalThis.wx = {
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  },
  removeStorageSync(key) {
    storage.delete(key);
  },
  getSetting({ success }) {
    success({ authSetting: Object.assign({}, settingValue) });
  },
  authorize({ scope, success, fail }) {
    calls.push(`authorize:${scope}`);
    if (authorizeBehavior === 'ok') {
      settingValue[scope] = true;
      success();
    } else {
      settingValue[scope] = false;
      fail(new Error('auth deny'));
    }
  },
  openSetting({ success }) {
    calls.push('openSetting');
    if (openSettingValue) settingValue['scope.userLocation'] = true;
    success({ authSetting: Object.assign({}, settingValue) });
  },
  getLocation({ success, fail }) {
    calls.push('getLocation');
    if (getLocationBehavior === 'ok') {
      success({ latitude: 28.2, longitude: 112.95 });
    } else {
      fail(new Error('locate fail'));
    }
  },
  showModal({ success }) {
    calls.push('showModal');
    success({ confirm: modalConfirm });
  },
  showToast() {},
  showLoading() {},
  hideLoading() {}
};

const store = require(path.join(root, 'utils/store.js'));
const location = require(path.join(root, 'utils/location.js'));

// 准备城市与门店数据
store.setCityCatalogForTest([
  { code: 'changsha', name: '长沙市', initial: 'C', latitude: 28.2282, longitude: 112.9388 },
  { code: 'shenzhen', name: '深圳市', initial: 'S', latitude: 22.5431, longitude: 114.0579 }
]);
store.setStoreCatalogForTest([
  {
    id: 'store-001',
    code: 'store-001',
    name: '长沙环宇城店',
    city: '长沙市',
    latitude: 28.2282,
    longitude: 112.9388,
    queueCount: 2
  }
]);

// 1. 未决定授权：应调用 wx.authorize，成功后取坐标并落库
storage.clear();
settingValue = {};
authorizeBehavior = 'ok';
getLocationBehavior = 'ok';
calls.length = 0;
let result = await location.locate({ force: true });
assert.deepEqual(calls, ['authorize:scope.userLocation', 'getLocation'], '未决定时必须先 authorize 再 getLocation');
assert.equal(result.granted, true, '授权成功必须标记 granted');
assert.equal(result.source, 'device', '拿到坐标时 source 必须是 device');
assert.equal(result.latitude, 28.2, '必须返回真实纬度');
assert.ok(result.locatedAt > 0, '必须写入定位时间戳');
assert.ok(location.hasDeviceLocation(), '定位成功后必须可读到设备坐标');

// 2. 缓存生效：10 分钟内不重复调用 getLocation
calls.length = 0;
result = await location.locate();
assert.equal(calls.length, 0, '缓存有效期内不得重复定位');
assert.equal(result.source, 'cache', '命中缓存时 source 必须是 cache');

// 3. 已拒绝授权：不再弹 authorize，改为 showModal 引导去设置页
storage.clear();
settingValue = { 'scope.userLocation': false };
getLocationBehavior = 'ok';
openSettingValue = true;
modalConfirm = true;
calls.length = 0;
result = await location.locate({ force: true });
assert.ok(calls.includes('showModal'), '已拒绝时必须弹窗引导去设置页');
assert.ok(calls.includes('openSetting'), '用户确认后必须调 openSetting');
assert.ok(!calls.some(item => item.startsWith('authorize:')), '已拒绝时不得再调 authorize（微信会直接失败）');
assert.equal(result.source, 'device', '设置页开启后必须能取到坐标');

// 4. 用户拒绝去设置：降级到城市中心坐标，不得 reject
storage.clear();
settingValue = { 'scope.userLocation': false };
modalConfirm = false;
calls.length = 0;
result = await location.locate({ force: true });
assert.equal(result.source, 'city', '拒绝授权必须降级到城市中心');
assert.equal(result.granted, false, '降级时 granted 必须为 false');
assert.equal(result.latitude, 28.2282, '降级必须用当前城市中心纬度');
assert.ok(!calls.includes('openSetting'), '用户取消时不得打开设置页');

// 5. 定位接口失败：同样降级，不得 reject
storage.clear();
settingValue = { 'scope.userLocation': true };
getLocationBehavior = 'fail';
result = await location.locate({ force: true });
assert.equal(result.source, 'city', '定位失败必须降级到城市中心');
assert.equal(result.granted, false, '定位失败时 granted 必须为 false');
getLocationBehavior = 'ok';

// 6. 就近城市匹配：坐标对应深圳时应匹配到深圳
storage.clear();
settingValue = { 'scope.userLocation': true };
const matched = location.matchNearestCity(22.55, 114.06);
assert.equal(matched.code, 'shenzhen', '坐标邻近深圳时必须匹配到深圳');
const matchedCs = location.matchNearestCity(28.23, 112.94);
assert.equal(matchedCs.code, 'changsha', '坐标邻近长沙时必须匹配到长沙');

// 7. clearDeviceLocation 后缓存失效
location.clearDeviceLocation();
assert.equal(location.hasDeviceLocation(), false, '清除后不得再判定为已定位');

// 8. 隐私合规：本模块不得程序化调起 getPhoneNumber / 静默采集
const source = require('node:fs').readFileSync(path.join(root, 'utils/location.js'), 'utf8');
assert.ok(
  !/getPhoneNumber|chooseAvatar|getUserProfile/.test(source),
  '定位模块不得涉及手机号/头像授权'
);
assert.ok(
  source.includes('scope.userLocation'),
  '定位模块必须显式使用 scope.userLocation'
);

// 9. app.json 必须已声明权限（与 location-permission.test.mjs 呼应）
const appJson = JSON.parse(require('node:fs').readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(
  appJson.permission && appJson.permission['scope.userLocation'],
  'app.json 必须声明 permission.scope.userLocation'
);
assert.ok(
  appJson.requiredPrivateInfos.includes('getLocation'),
  'requiredPrivateInfos 必须包含 getLocation'
);

console.log('设备定位授权、降级与就近城市匹配测试通过');