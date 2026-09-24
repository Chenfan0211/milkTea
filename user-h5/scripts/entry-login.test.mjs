import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const storage = new Map();
let wxLoginCalls = 0;
let wxLoginBehavior = 'ok';

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
  showToast() {},
  login({ success, fail }) {
    wxLoginCalls += 1;
    if (wxLoginBehavior === 'ok') success({ code: 'code-' + wxLoginCalls });
    else fail(new Error('wx.login failed'));
  },
  request({ url, success, fail }) {
    if (url.indexOf('/auth/wx-login') >= 0) {
      if (wxLoginBehavior === 'ok') {
        success({ data: { code: 0, data: { token: 'token-1', userId: 1, openId: 'openid-1' }, message: 'ok' } });
        return;
      }
      fail(new Error('network down'));
      return;
    }
    // /auth/me
    success({ data: { code: 0, data: { id: 1, nickName: '测试用户', phone: '' }, message: 'ok' } });
  }
};

const entry = require(path.join(root, 'utils/entry-login.js'));
const auth = require(path.join(root, 'utils/auth.js'));
const guard = require(path.join(root, 'utils/login-guard.js'));

// 1. 未登录时 ensureEntryLogin 必须真正换取 token
const okResult = await entry.ensureEntryLogin();
assert.equal(okResult.ok, true, '静默登录成功时 ok 必须为 true');
assert.equal(okResult.timedOut, false, '未超时不得标记 timedOut');
assert.ok(auth.getToken(), '静默登录后必须写入 token');
assert.equal(okResult.state.hasToken, true, '返回态必须标记已登录');

// 2. 已登录时不得重复 wx.login（并发去重与缓存复用）
const callsBefore = wxLoginCalls;
await entry.ensureEntryLogin();
assert.equal(wxLoginCalls, callsBefore, '已登录时不得重复调用 wx.login');

// 3. 登录失败时永不 reject，且必须放行
auth.clearSession();
guard.__resetForTest();
wxLoginBehavior = 'fail';
const failResult = await entry.ensureEntryLogin();
assert.equal(failResult.ok, false, '登录失败时 ok 必须为 false');
assert.ok(failResult.error, '登录失败必须回传 error 供排查');
assert.equal(failResult.state.hasToken, false, '登录失败必须保持未登录态');
wxLoginBehavior = 'ok';

// 4. 超时兜底：wx.login 永不回调时必须在超时后放行
auth.clearSession();
guard.__resetForTest();
wx.login = () => {
  /* 故意不回调，模拟卡死 */
};
const start = Date.now();
const timeoutResult = await entry.ensureEntryLogin({ timeout: 120 });
const elapsed = Date.now() - start;
assert.equal(timeoutResult.ok, false, '超时必须放行且 ok 为 false');
assert.equal(timeoutResult.timedOut, true, '超时必须标记 timedOut');
assert.ok(elapsed >= 120 && elapsed < 1500, `超时兜底必须在约定时间附近返回，实际 ${elapsed}ms`);

// 5. 引导去重：首次可引导，标记后会话内与冷却期内不再引导
storage.clear();
entry.__resetForTest();
assert.equal(entry.shouldPromptEntry(1000), true, '未引导过时必须引导');
entry.markEntryPrompted(1000);
assert.equal(entry.shouldPromptEntry(1000 + entry.ENTRY_PROMPT_COOLDOWN - 1), false, '冷却期内不得重复引导');
entry.__resetForTest();
assert.equal(
  entry.shouldPromptEntry(1000 + entry.ENTRY_PROMPT_COOLDOWN + 1),
  true,
  '冷却期结束后可再次引导'
);

// 6. 开关关闭时永不引导（合规兜底）
entry.setPromptEnabled(false);
storage.clear();
entry.__resetForTest();
assert.equal(entry.shouldPromptEntry(9000), false, '关闭引导开关后不得引导');
entry.setPromptEnabled(true);

// 7. 入口还原：只认白名单，其余回首页
assert.equal(entry.resolveEntryTarget(), '/pages/home/home', '无参数时必须回首页');
assert.equal(
  entry.resolveEntryTarget({ from: 'pages/coupon-stores/coupon-stores', query: 'couponId=9' }),
  '/pages/coupon-stores/coupon-stores?couponId=9',
  '白名单来源必须还原页面与安全参数'
);
assert.equal(
  entry.resolveEntryTarget({ from: 'pages/role-withdraw/role-withdraw', query: 'amount=999' }),
  '/pages/home/home',
  '非白名单来源必须回首页，防止被构造参数跳转'
);
assert.equal(
  entry.resolveEntryTarget({ from: 'pages/coupon-stores/coupon-stores', query: 'evil=1' }),
  '/pages/coupon-stores/coupon-stores',
  '白名单外的 query 参数必须被丢弃'
);
assert.equal(
  entry.buildLaunchQuery({ from: 'pages/coupon-stores/coupon-stores', query: 'couponId=9' }),
  'from=pages%2Fcoupon-stores%2Fcoupon-stores&query=couponId%3D9',
  '跳启动页的上下文必须编码'
);


// 8. 登录失败 / 超时时必须标记 ok=false，调用方据此跳过引导（不跳授权页）
//    这是启动页「登录失败直接放行」判定的契约来源。
auth.clearSession();
guard.__resetForTest();
const failFlag = await entry.ensureEntryLogin();
assert.equal(failFlag.ok, false, '无 token 时 ok 必须为 false');
assert.equal(failFlag.state.hasToken, false, '无 token 时 state.hasToken 必须为 false，供启动页判定跳过引导');
console.log('入口静默登录、超时兜底与引导去重测试通过');

