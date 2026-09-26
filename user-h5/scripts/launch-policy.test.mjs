import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

function stubModule(relativePath, exports) {
  const file = require.resolve(path.join(root, relativePath));
  require.cache[file] = { id: file, filename: file, loaded: true, exports };
}

let pageDefinition = null;
let navigated = [];
let capturedReferrer = null;
const entryStub = {
  HOME_PATH: '/pages/home/home',
  ensureEntryLogin: () => Promise.resolve({ ok: false, state: { level: 'anonymous', hasToken: false } }),
  resolveEntryTarget: () => '/pages/coupon-stores/coupon-stores?couponId=9',
  shouldPromptEntry: () => true,
  markEntryPrompted() {}
};
stubModule('utils/share.js', { withShare: options => options });
stubModule('utils/entry-login.js', entryStub);
stubModule('utils/navigate.js', {
  go(target) {
    navigated.push(target);
  }
});
stubModule('utils/referrer.js', {
  captureFromOptions(options) {
    capturedReferrer = options && options.referrerId;
  }
});
globalThis.Page = definition => {
  pageDefinition = definition;
};
delete require.cache[require.resolve(path.join(root, 'pages/launch/launch.js'))];
require(path.join(root, 'pages/launch/launch.js'));

async function runLaunch(loginResult) {
  navigated = [];
  entryStub.ensureEntryLogin = () => Promise.resolve(loginResult);
  pageDefinition.onLoad.call(pageDefinition, { referrerId: '88' });
  await Promise.resolve();
  await Promise.resolve();
  return navigated.slice();
}

const publicTarget = '/pages/coupon-stores/coupon-stores?couponId=9';
let result = await runLaunch({
  ok: true,
  needsRegister: true,
  state: { level: 'anonymous', hasToken: false }
});
assert.deepEqual(result, [publicTarget], '未注册用户必须直接进入公开目标页');
assert.equal(capturedReferrer, '88', '启动页必须保留邀请人捕获');

result = await runLaunch({
  ok: false,
  error: new Error('wx.login failed'),
  state: { level: 'anonymous', hasToken: false }
});
assert.deepEqual(result, [publicTarget], '登录失败必须直接进入公开目标页');

result = await runLaunch({
  ok: false,
  timedOut: true,
  state: { level: 'anonymous', hasToken: false }
});
assert.deepEqual(result, [publicTarget], '登录超时必须直接进入公开目标页');

result = await runLaunch({
  ok: true,
  state: { level: 'phone', hasToken: true }
});
assert.deepEqual(result, [publicTarget], '已登录未绑手机号必须直接进入公开目标页');

delete globalThis.Page;
console.log('启动页公开入口放行策略测试通过');