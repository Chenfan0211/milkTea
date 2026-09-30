import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const storage = new Map();
let loginCalls = 0;
let loginPayload = { token: 'token-fresh', userId: 1, openId: 'openid-1' };
let toastMessages = [];

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
  showToast({ title }) {
    toastMessages.push(title);
  },
  login({ success }) {
    loginCalls += 1;
    success({ code: `code-${loginCalls}` });
  },
  request({ url, success }) {
    if (url.indexOf('/api/v1/app/auth/wx-login') >= 0) {
      success({ data: { code: 0, data: loginPayload, message: 'ok' } });
      return;
    }
    success({ data: { code: 0, data: {}, message: 'ok' } });
  }
};

const auth = require(path.join(root, 'utils/auth.js'));

// 1. 即使本地已有 token，也必须重新 wx.login，刷新最新 session_key。
auth.saveSession({ token: 'token-stale', userId: 1, openId: 'openid-1' });
await auth.preparePhoneAuthorization();
assert.equal(loginCalls, 1, '已有旧 token 时仍必须调用 wx.login 刷新微信会话');
assert.equal(auth.getToken(), 'token-fresh', '刷新成功后必须覆盖旧 token');

// 2. 独立单飞：并发准备只能产生一次 wx.login。
auth.__resetForTest();
const callsBeforeSingleFlight = loginCalls;
const first = auth.preparePhoneAuthorization();
const second = auth.preparePhoneAuthorization();
assert.equal(first, second, '并发准备必须复用同一个 Promise');
await Promise.all([first, second]);
assert.equal(loginCalls, callsBeforeSingleFlight + 1, '并发准备只能调用一次 wx.login');

// 3. 未注册刷新必须返回最新注册上下文，供手机号按钮使用。
auth.__resetForTest();
auth.clearSession();
auth.clearRegisterContext();
loginPayload = { registered: false, registerToken: 'register-fresh', openId: 'openid-new' };
const prepared = await auth.preparePhoneAuthorization();
assert.equal(prepared.needRegister, true, '未注册态必须标记 needRegister');
assert.equal(prepared.registerContext.registerToken, 'register-fresh', '必须返回最新注册凭证');
assert.equal(auth.isLoggedIn(), false, '未注册态不得保留旧登录态');

// 4. 统一识别需要刷新会话的失效错误。
for (const message of ['登录状态已失效，请重新登录', '微信数据解密失败', '微信授权已失效，请重新登录']) {
  assert.equal(auth.isSessionInvalidError(new Error(message)), true, `必须识别失效错误：${message}`);
}
assert.equal(
  auth.isSessionInvalidError(Object.assign(new Error('未登录'), { res: { code: 8888 } })),
  true,
  '必须识别 8888/9999 登录失效业务码'
);
assert.equal(auth.isSessionInvalidError(new Error('网络不可用')), false, '普通网络错误不得误判为会话失效');

// 5. 弹层结构：保留原生手机号授权、协议、跳过和完整可点击语义。
const wxml = fs.readFileSync(path.join(root, 'components/login-sheet/login-sheet.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(root, 'components/login-sheet/login-sheet.wxss'), 'utf8');
assert.match(wxml, /open-type="getPhoneNumber"/, '必须保留原生手机号授权按钮');
assert.match(wxml, /aria-checked="\{\{agreementChecked\}\}"/, '协议勾选必须保留无障碍状态');
assert.match(wxml, /bindtap="handleSkip"/, '必须保留跳过入口');
assert.match(wxml, /bindtap="handleClose"/, '必须保留关闭入口');
assert.match(wxss, /z-index:\s*12000;/, '弹层层级必须高于 custom-tab-bar 的 10000');
assert.match(wxss, /height:\s*88rpx;/, '主按钮高度必须为 88rpx（对齐参考图）');
assert.match(
  wxss,
  /\.login-sheet__primary\s*\{[\s\S]*?border-radius:\s*var\(--radius-pill\);/,
  '主按钮必须使用胶囊圆角（对齐参考图）'
);
assert.match(
  wxss,
  /padding:\s*40rpx 32rpx calc\(var\(--tabbar-height\) \+ 32rpx \+ env\(safe-area-inset-bottom\)\)/,
  '面板左右保留边距（按钮不铺满），底部内缩让开 TabBar'
);
// 等宽约束：原生 button 会被框架注入默认样式导致宽度与跳过按钮不一致，
// 必须用 wrap 容器锁定满宽，两个按钮才等宽。
assert.match(wxml, /login-sheet__primary-wrap/, '主按钮必须包裹满宽容器，保证与跳过按钮等宽');
assert.match(wxss, /\.login-sheet__primary-wrap\s*\{[\s\S]*?width:\s*100%/, '主按钮容器必须满宽');
assert.match(wxss, /\.login-sheet__skip\s*\{[\s\S]*?width:\s*100%/, '跳过按钮必须满宽，与主按钮一致');
assert.match(
  wxss,
  /\.login-sheet__primary\s*\{[\s\S]*?flex:\s*1;/,
  '主按钮必须用 flex:1 撑满容器（原生 button 的固有宽度会抵抗 width:100%）'
);
assert.match(wxss, /\.login-sheet__primary::after\s*\{\s*border:\s*none;\s*\}/, '原生授权按钮必须重置 ::after 边框');
// 面板必须贴底 + 只做上圆角（脱离底边后四角圆角会呈「椭圆胶囊」观感），并用底部内边距让开 TabBar。
assert.match(wxss, /max-height:\s*calc\(100vh/, '登录面板必须限高，避免内容超出屏幕');
assert.match(wxss, /bottom:\s*0;/, '登录面板必须贴屏幕底部');
assert.match(
  wxss,
  /border-radius:\s*var\(--radius-lg\) var\(--radius-lg\) 0 0;/,
  '登录面板只能做上圆角，避免呈椭圆观感'
);
assert.match(wxml, /login-sheet__body/, '登录面板必须包裹内容滚动容器');
assert.match(wxss, /\.login-sheet__body\s*\{[\s\S]*?overflow-y:\s*auto/, '登录面板内容区必须可滚动，保证协议区可达');
// 品牌还原约束：logo 与主题色必须保留自有品牌，不照搬参考图（图 2）的第三方 logo 与配色。
assert.match(wxml, /leaf-white\.svg/, '登录弹层 logo 必须使用自有叶子图标，不得替换为参考图第三方 logo');
assert.match(wxss, /var\(--brand-green\)/, '登录弹层主按钮必须使用自有主题色 token，不得写死参考图第三方色值');
const requestSource = fs.readFileSync(path.join(root, 'utils/request.js'), 'utf8');
const authSource = fs.readFileSync(path.join(root, 'utils/auth.js'), 'utf8');
assert.match(requestSource, /options\.retryAuth !== false/, '请求层必须允许加密数据请求关闭自动重登重试');
assert.match(
  authSource,
  /function bindPhone\([\s\S]*?retryAuth:\s*false/,
  '手机号绑定请求不得自动复用旧 encryptedData/iv 重试'
);

// 6. 弹层刷新未完成时，不得使用当前点击生成的旧 encryptedData/iv 发请求。
function stubModule(relativePath, exports) {
  const file = require.resolve(path.join(root, relativePath));
  require.cache[file] = { id: file, filename: file, loaded: true, exports };
}

let capturedComponent = null;
let bindPhoneCalls = 0;
let registerByPhoneCalls = [];
let clearSessionCalls = 0;
let latestPrepareCalls = 0;
let prepareResult = new Promise(() => {});
const authStub = {
  getRegisterContext() {
    return { registerToken: 'stale-register-token' };
  },
  preparePhoneAuthorization() {
    latestPrepareCalls += 1;
    return prepareResult;
  },
  isSessionInvalidError(error) {
    return /微信数据解密失败/.test(error && error.message);
  },
  clearSession() {
    clearSessionCalls += 1;
  },
  clearRegisterContext() {},
  registerByPhone(registerToken) {
    registerByPhoneCalls.push(registerToken);
    return Promise.resolve({ token: 'registered' });
  }
};
const guardStub = {
  toast(message) {
    toastMessages.push(message);
  }
};
const apiStub = {
  bindPhone() {
    bindPhoneCalls += 1;
    return Promise.resolve({});
  }
};
stubModule('utils/auth.js', authStub);
stubModule('utils/login-guard.js', guardStub);
stubModule('utils/api.js', apiStub);
globalThis.Component = definition => {
  capturedComponent = definition;
};
delete require.cache[require.resolve(path.join(root, 'components/login-sheet/login-sheet.js'))];
require(path.join(root, 'components/login-sheet/login-sheet.js'));
assert.ok(capturedComponent, '组件必须完成注册');

function createInstance() {
  const instance = {
    data: { agreementChecked: true },
    setData(patch) {
      Object.assign(this.data, patch);
    },
    triggerEvent() {}
  };
  for (const [name, method] of Object.entries(capturedComponent.methods || {})) {
    instance[name] = method;
  }
  return instance;
}

const pendingInstance = createInstance();
capturedComponent.lifetimes.attached.call(pendingInstance);
pendingInstance.setData({ agreementChecked: true });
await Promise.resolve();
toastMessages = [];
pendingInstance.handleGetPhoneNumber({ detail: { encryptedData: 'stale-data', iv: 'stale-iv' } });
assert.equal(bindPhoneCalls, 0, '会话准备期间不得提交旧 encryptedData/iv');
assert.ok(toastMessages.includes('正在准备登录，请稍后重试'), '准备期间必须提示用户稍后重试');

// 7. 准备成功后必须使用最新 registerContext，而不是旧本地上下文。
let resolvePrepare;
prepareResult = new Promise(resolve => {
  resolvePrepare = resolve;
});
const latestInstance = createInstance();
capturedComponent.lifetimes.attached.call(latestInstance);
latestInstance.setData({ agreementChecked: true });
resolvePrepare({ needRegister: true, registerContext: { registerToken: 'fresh-register-token' } });
await Promise.resolve();
await Promise.resolve();
await latestInstance.handleGetPhoneNumber({ detail: { encryptedData: 'fresh-data', iv: 'fresh-iv' } });
assert.deepEqual(registerByPhoneCalls, ['fresh-register-token'], '必须使用刷新后的最新注册凭证');

// 8. 会话失效后只刷新会话并要求再次点击，不自动复用旧 encryptedData/iv 重试。
authStub.preparePhoneAuthorization = () => {
  latestPrepareCalls += 1;
  return Promise.resolve({ needRegister: false, registerContext: null });
};
apiStub.bindPhone = () => {
  bindPhoneCalls += 1;
  return Promise.reject(new Error('微信数据解密失败'));
};
const invalidInstance = createInstance();
capturedComponent.lifetimes.attached.call(invalidInstance);
invalidInstance.setData({ agreementChecked: true });
await Promise.resolve();
await Promise.resolve();
toastMessages = [];
await invalidInstance.handleGetPhoneNumber({ detail: { encryptedData: 'expired-data', iv: 'expired-iv' } });
assert.equal(bindPhoneCalls, 1, '旧加密数据只允许尝试一次，失败后不得自动重试');
assert.equal(clearSessionCalls, 1, '会话失效后必须清理失效登录态');
assert.ok(toastMessages.includes('授权会话已刷新，请再次点击同意'), '必须提示用户重新点击授权');
delete globalThis.Component;

console.log('登录弹层、手机号授权刷新与会话失效测试通过');
