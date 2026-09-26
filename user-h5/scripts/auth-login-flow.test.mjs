import assert from 'node:assert/strict';
import fs from 'node:fs';
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
let bindPhoneCalls = 0;
let registerByPhoneCalls = [];
let clearSessionCalls = 0;
let clearRegisterCalls = 0;
let toastMessages = [];
let prepareResult = Promise.resolve({ needRegister: false, registerContext: null });

const authStub = {
  getRegisterContext() {
    return { registerToken: 'stale-register-token' };
  },
  preparePhoneAuthorization() {
    return prepareResult;
  },
  isLoggedIn() {
    return false;
  },
  isSessionInvalidError(error) {
    return /微信数据解密失败/.test(error && error.message);
  },
  clearSession() {
    clearSessionCalls += 1;
  },
  clearRegisterContext() {
    clearRegisterCalls += 1;
  },
  registerByPhone(registerToken) {
    registerByPhoneCalls.push(registerToken);
    return Promise.resolve({ token: 'registered' });
  }
};

stubModule('utils/share.js', { withShare: options => options });
stubModule('utils/api.js', {
  bindPhone() {
    bindPhoneCalls += 1;
    return Promise.resolve({});
  }
});
stubModule('utils/login-guard.js', {
  toast(message) {
    toastMessages.push(message);
  },
  clearPendingAction() {}
});
stubModule('utils/auth.js', authStub);
stubModule('utils/navigate.js', { go() {} });

globalThis.Page = definition => {
  pageDefinition = definition;
};
delete require.cache[require.resolve(path.join(root, 'pages/auth-login/auth-login.js'))];
require(path.join(root, 'pages/auth-login/auth-login.js'));
assert.ok(pageDefinition, '授权页必须完成注册');

const wxml = fs.readFileSync(path.join(root, 'pages/auth-login/auth-login.wxml'), 'utf8');
assert.match(wxml, /open-type="getPhoneNumber"/, '授权页必须保留原生手机号授权按钮');
assert.match(wxml, /bindgetphonenumber="handleAgree"/, '授权页必须绑定现有授权处理函数');

function createPage() {
  const page = {
    data: { agreementChecked: false, needRegister: false },
    setData(patch) {
      Object.assign(this.data, patch);
    }
  };
  for (const [name, method] of Object.entries(pageDefinition)) {
    if (typeof method === 'function') page[name] = method;
  }
  return page;
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

let resolvePrepare;
prepareResult = new Promise(resolve => {
  resolvePrepare = resolve;
});
const pendingPage = createPage();
pageDefinition.onLoad.call(pendingPage, { mode: 'entry', target: encodeURIComponent('/pages/home/home') });
pendingPage.setData({ agreementChecked: true });
toastMessages = [];
pendingPage.handleAgree({ detail: { encryptedData: 'stale-data', iv: 'stale-iv' } });
assert.equal(bindPhoneCalls, 0, '授权页会话准备期间不得提交旧 encryptedData/iv');
assert.ok(toastMessages.includes('正在准备登录，请稍后重试'), '授权页准备期间必须提示稍后重试');

resolvePrepare({ needRegister: true, registerContext: { registerToken: 'fresh-register-token' } });
await flushPromises();
await pendingPage.handleAgree({ detail: { encryptedData: 'fresh-data', iv: 'fresh-iv' } });
assert.deepEqual(registerByPhoneCalls, ['fresh-register-token'], '授权页必须使用刷新后的最新注册凭证');

prepareResult = Promise.resolve({ needRegister: false, registerContext: null });
const invalidPage = createPage();
pageDefinition.onLoad.call(invalidPage, { mode: 'entry', target: encodeURIComponent('/pages/home/home') });
await flushPromises();
invalidPage.setData({ agreementChecked: true });
stubModule('utils/api.js', {
  bindPhone() {
    bindPhoneCalls += 1;
    return Promise.reject(new Error('微信数据解密失败'));
  }
});
delete require.cache[require.resolve(path.join(root, 'pages/auth-login/auth-login.js'))];
// 重新加载页面定义，确保使用上面替换后的 api stub。
pageDefinition = null;
require(path.join(root, 'pages/auth-login/auth-login.js'));
const expiredPage = createPage();
pageDefinition.onLoad.call(expiredPage, { mode: 'entry', target: encodeURIComponent('/pages/home/home') });
await flushPromises();
expiredPage.setData({ agreementChecked: true });
toastMessages = [];
await expiredPage.handleAgree({ detail: { encryptedData: 'expired-data', iv: 'expired-iv' } });
assert.equal(bindPhoneCalls, 1, '授权页旧加密数据失败后不得自动重试');
assert.equal(clearSessionCalls, 1, '授权页会话失效后必须清理失效登录态');
assert.equal(clearRegisterCalls, 1, '授权页会话失效后必须清理旧注册上下文');
assert.ok(toastMessages.includes('授权会话已刷新，请再次点击同意'), '授权页必须提示用户重新点击授权');

delete globalThis.Page;
console.log('授权页手机号授权刷新与会话失效测试通过');
