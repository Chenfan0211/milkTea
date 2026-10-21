import assert from 'node:assert/strict';
import test from 'node:test';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = {};
const calls = [];
const app = { globalData: {} };

globalThis.getApp = () => app;

globalThis.wx = {
  showShareMenu() {},
  getStorageSync: key => (storage[key] === undefined ? '' : storage[key]),
  setStorageSync: (key, value) => {
    storage[key] = value;
  },
  removeStorageSync: key => {
    delete storage[key];
  },
  showToast(options) {
    calls.push({ type: 'toast', ...(options || {}) });
  },
  showLoading(options) {
    calls.push({ type: 'loading', ...(options || {}) });
  },
  hideLoading() {
    calls.push({ type: 'hideLoading' });
  },
  navigateTo(options) {
    calls.push({ type: 'navigateTo', ...(options || {}) });
  }
};

const api = require(path.join(root, 'utils/api.js'));
const auth = require(path.join(root, 'utils/auth.js'));
const loginGuard = require(path.join(root, 'utils/login-guard.js'));
const tongueProfile = require(path.join(root, 'utils/tongue-profile.js'));

api.fetchMe = () => Promise.resolve({ gender: 'female', birthday: '1998-04-05' });
auth.saveSession({ token: 'tongue-token', userId: 11 });
auth.isLoggedIn = () => true;
loginGuard.requireLogin = action => Promise.resolve().then(action);

let definition;
globalThis.Page = page => {
  definition = page;
};
require(path.join(root, 'pages/tongue/tongue.js'));

function resetPage() {
  Object.keys(storage).forEach(key => delete storage[key]);
  calls.length = 0;
  const page = Object.assign({}, definition);
  page.data = Object.assign({}, definition.data);
  page.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return page;
}

test('每次点击测试都会加载资料并弹出个人信息层', async () => {
  const page = resetPage();

  await definition.startDetect.call(page);

  assert.equal(page.data.sheetVisible, true);
  assert.equal(page.data.sheetGender, 'female');
  assert.equal(page.data.sheetBirthYear, '1998');
  assert.equal(
    calls.some(call => call.type === 'navigateTo'),
    false,
    '仅点击测试不得直接进入拍摄页'
  );
});

test('保存弹层只写舌诊缓存并直接进入拍摄页', async () => {
  const page = resetPage();
  storage[tongueProfile.TONGUE_PROFILE_STORAGE_KEY] = { gender: 'female', birthYear: '1998' };
  let startDetectCalls = 0;
  page.startDetect = () => {
    startDetectCalls += 1;
  };
  let profileWriteCalls = 0;
  api.updateProfileFields = () => {
    profileWriteCalls += 1;
    return Promise.resolve({});
  };

  definition.handleSheetSave.call(page, {
    detail: { gender: 'male', birthYear: '2006' }
  });
  await new Promise(resolve => setTimeout(resolve, 0));

  assert.deepEqual(storage[tongueProfile.TONGUE_PROFILE_STORAGE_KEY], {
    gender: 'male',
    birthYear: '2006'
  });
  assert.equal(profileWriteCalls, 0, '保存舌诊资料不得写用户表');
  assert.equal(startDetectCalls, 0, '保存后不得再次经过开始测试入口');
  assert.ok(
    calls.some(call => call.type === 'navigateTo' && call.url.endsWith('/packageHealth/pages/capture/capture'))
  );
});

test('第二次点击测试优先显示上次保存的本地资料', async () => {
  const page = resetPage();
  storage[tongueProfile.TONGUE_PROFILE_STORAGE_KEY] = { gender: 'male', birthYear: '2006' };
  let fetchCalls = 0;
  api.fetchMe = () => {
    fetchCalls += 1;
    return Promise.resolve({ gender: 'female', birthday: '1998-04-05' });
  };

  await definition.startDetect.call(page);

  assert.equal(page.data.sheetGender, 'male');
  assert.equal(page.data.sheetBirthYear, '2006');
  assert.equal(fetchCalls, 0, '有本地缓存时不得请求账号资料');
});

test('关闭个人信息层不会跳转拍摄页', () => {
  const page = resetPage();
  page.setData({ sheetVisible: true });

  definition.closeProfileSheet.call(page);

  assert.equal(page.data.sheetVisible, false);
  assert.equal(
    calls.some(call => call.type === 'navigateTo'),
    false
  );
});

test('舌诊页不接管推荐购物车', () => {
  const page = resetPage();
  page.onShow();

  assert.equal(page.data.cartCount, undefined);
  assert.equal(page.data.cartVisible, undefined);
  assert.equal(definition.handleCartOpen, undefined);
  assert.equal(definition.handleCartCheckout, undefined);
});
