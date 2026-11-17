import assert from 'node:assert/strict';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const modulePath = path.join(root, 'utils/tab-bar-config.js');
const storage = {};
const switches = [];
let accountVersion = '';

globalThis.wx = {
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  getAccountInfoSync() {
    return { miniProgram: { version: accountVersion, envVersion: 'release' } };
  },
  switchTab(options) {
    switches.push(options.url);
  }
};

function loadModule() {
  delete require.cache[require.resolve(modulePath)];
  return require(modulePath);
}

const policy = loadModule();
const config = {
  schemaVersion: 2,
  defaultThirdTab: 'memberZone',
  releases: {
    '1.3.5': 'memberZone',
    '1.3.6': 'tongueHealth'
  }
};

assert.deepEqual(policy.normalize(config), config);
assert.equal(policy.resolveThirdTab(config, '1.3.5'), 'memberZone');
assert.equal(policy.resolveThirdTab(config, '1.3.6'), 'tongueHealth');
assert.equal(policy.resolveThirdTab(config, '1.3.7'), 'memberZone');
assert.equal(policy.resolveThirdTab({ ...config, defaultThirdTab: 'tongueHealth' }, '1.3.7'), 'tongueHealth');

assert.throws(() => policy.normalize({ ...config, schemaVersion: 1 }), /schemaVersion/);
assert.throws(() => policy.normalize({ ...config, defaultThirdTab: 'unknown' }), /defaultThirdTab/);
assert.throws(() => policy.normalize({ ...config, releases: [] }), /releases/);
assert.throws(() => policy.normalize({ ...config, releases: { '1.3/5': 'memberZone' } }), /版本号/);
assert.throws(() => policy.normalize({ ...config, releases: { '1.3.5': 'unknown' } }), /releases/);
assert.throws(() => policy.normalize({ ...config, pagePath: '/pages/home/home' }), /仅支持/);

accountVersion = '1.3.6';
assert.equal(policy.resolveAppVersion(), '1.3.6', '正式或体验版必须优先使用微信运行版本');
accountVersion = '';
assert.equal(policy.resolveAppVersion(), '1.3.24', '体验版无法读取微信版本号时必须使用本次源码构建版本');
assert.equal(
  policy.resolveThirdTab(
    { schemaVersion: 2, defaultThirdTab: 'memberZone', releases: { '1.3.24': 'tongueHealth' } },
    policy.resolveAppVersion()
  ),
  'tongueHealth',
  '体验版必须使用源码构建版本匹配一人一饮'
);

policy.resetForTest();
let requests = 0;
const member = await policy.ready(
  async (key, options) => {
    requests += 1;
    assert.equal(key, 'mini_tab_bar');
    assert.equal(options.timeout, 1500);
    return config;
  },
  { version: '1.3.5' }
);
assert.equal(requests, 1, '一次冷启动只能请求一次导航配置');
assert.equal(member.thirdTab, 'memberZone');
assert.equal(member.appVersion, '1.3.5');
assert.equal(policy.isMemberZoneTab(), true);
assert.deepEqual(
  policy.getTabBarItems().map(item => item.text),
  ['首页', '点单', '会员专区', '订单', '我的']
);
assert.equal(policy.getThirdTabPath(), '/pages/feature-tab/feature-tab');
assert.equal(policy.getTabBarSelectedIndex('/pages/feature-tab/feature-tab'), 2);
assert.equal(policy.shouldHideRoute('/pages/tongue/tongue'), true);
assert.equal(policy.shouldHideRoute('/packageHealth/pages/report/report?id=8'), true);
assert.equal(policy.redirectIfHidden('/pages/tongue/tongue'), true);
assert.deepEqual(switches, ['/pages/feature-tab/feature-tab']);

await Promise.all([
  policy.ready(async () => assert.fail('共享请求不得重复发起'), { version: '1.3.5' }),
  policy.ready(async () => assert.fail('共享请求不得重复发起'), { version: '1.3.5' })
]);
assert.equal(requests, 1);

policy.resetForTest();
const tongue = await policy.ready(async () => config, { version: '1.3.6' });
assert.equal(tongue.thirdTab, 'tongueHealth');
assert.equal(tongue.appVersion, '1.3.6');
assert.equal(policy.isMemberZoneTab(), false);
assert.deepEqual(
  policy.getTabBarItems().map(item => item.text),
  ['首页', '点单', '一人一饮', '订单', '我的']
);
assert.equal(policy.shouldHideRoute('/pages/tongue/tongue'), false);
switches.length = 0;
assert.equal(policy.redirectIfHidden('/pages/tongue/tongue'), false);
assert.deepEqual(switches, [], '一人一饮模式不得拦截舌诊路由');

policy.resetForTest();
const unknownVersion = await policy.ready(async () => config, { version: '1.3.7' });
assert.equal(unknownVersion.thirdTab, 'memberZone', '未登记版本必须使用默认第三项');

policy.resetForTest();
const cachedFallback = await policy.ready(
  async () => {
    throw new Error('offline');
  },
  { version: '1.3.6' }
);
assert.equal(cachedFallback.thirdTab, 'tongueHealth', '接口失败时必须使用同版本上次成功缓存');
assert.equal(policy.getLoadSource(), 'cache');

policy.resetForTest();
const isolatedVersion = await policy.ready(
  async () => {
    throw new Error('offline');
  },
  { version: '1.3.5' }
);
assert.equal(isolatedVersion.thirdTab, 'memberZone', '1.3.6 的缓存不得影响 1.3.5');
assert.equal(policy.getLoadSource(), 'cache');

const cache135 = policy.cacheKeyForVersion('1.3.5');
storage[cache135] = JSON.stringify({ schemaVersion: 2, thirdTab: 'tongueHealth' });
policy.resetForTest();
const invalidRemote = await policy.ready(async () => ({ ...config, releases: 'broken' }), { version: '1.3.5' });
assert.equal(invalidRemote.thirdTab, 'tongueHealth', '非法远端配置不得覆盖当前版本有效缓存');
assert.equal(policy.getLoadSource(), 'cache');

const cache137 = policy.cacheKeyForVersion('1.3.7');
storage[cache137] = JSON.stringify({ schemaVersion: 2, thirdTab: 'unknown' });
policy.resetForTest();
const invalidFallback = await policy.ready(
  async () => {
    throw new Error('offline');
  },
  { version: '1.3.7' }
);
assert.equal(invalidFallback.thirdTab, 'memberZone', '损坏缓存不得阻止默认值');

console.log('runtime-tab-bar.test.mjs: passed');
