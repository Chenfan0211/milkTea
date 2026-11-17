import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { beforeEach, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const api = require('../utils/api');
const auth = require('../utils/auth');
const entryLogin = require('../utils/entry-login');
const mallLeaderShare = require('../utils/mall-leader-share');
const referrer = require('../utils/referrer');
const storage = new Map();
const bindings = [];
let attribution;
let attributionFailure;
let bindReply;
let orderPayload;
const tick = () => new Promise(resolve => setImmediate(resolve));

global.wx = {
  getStorageSync: key => storage.get(key) || '',
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  showShareMenu() {},
  showLoading() {},
  hideLoading() {},
  request({ url, data, header, success, fail }) {
    if (url.endsWith('/app/leader/attribution')) {
      assert.equal(header.Authorization, 'Bearer test-session');
      if (attributionFailure) return fail({ errMsg: 'attribution unavailable' });
      return success({ data: { code: 0, data: attribution } });
    }
    if (url.endsWith('/app/leader/bind-share')) {
      assert.equal(header.Authorization, 'Bearer test-session');
      bindings.push(data);
      return bindReply ? success({ data: bindReply }) : fail({ errMsg: 'network unavailable' });
    }
    if (url.endsWith('/app/auth/register-by-sms')) {
      assert.equal(data.referrerId, 51);
      return success({ data: { code: 0, data: { userId: 50, token: 'test-session' } } });
    }
    throw new Error('Unexpected request ' + url);
  }
};

api.fetchMallCategories = async () => [];
api.fetchMallProducts = async () => ({ records: [] });
api.fetchMallProductDetail = async id => ({ id, skus: [] });
api.fetchMyRoles = async () => ({ roles: [] });
api.createMallOrder = async payload => {
  orderPayload = payload;
  throw new Error('stop before payment');
};

function page(name) {
  const route = 'packageMall/pages/' + name + '/' + name;
  const filename = fileURLToPath(new URL('../' + route + '.js', import.meta.url));
  let definition;
  vm.runInNewContext(
    fs.readFileSync(filename, 'utf8'),
    {
      Page: value => {
        definition = value;
      },
      require: createRequire(filename),
      wx: global.wx
    },
    { filename }
  );
  return {
    ...definition,
    route,
    data: structuredClone(definition.data),
    setData(patch) {
      Object.assign(this.data, patch);
    }
  };
}

function signIn(userId = 50) {
  auth.saveSession({ userId, token: 'test-session' });
}

beforeEach(async () => {
  await tick();
  storage.clear();
  bindings.length = 0;
  attribution = { leader: false, leaderUserId: null, leaderSubjectId: null };
  attributionFailure = false;
  bindReply = { code: 0, data: { leader: false, leaderUserId: 73, leaderSubjectId: 31 } };
  orderPayload = undefined;
});

for (const { name, leader, leaderUserId, expected } of [
  { name: '团长分享自己', leader: true, leaderUserId: 50, expected: '&leaderUserId=50' },
  { name: '普通用户转发已归属的团长', leader: false, leaderUserId: 73, expected: '&leaderUserId=73' },
  { name: '无团长用户只分享推荐人', leader: false, leaderUserId: null, expected: '' }
]) {
  test(name, async () => {
    signIn();
    attribution = { leader, leaderUserId, leaderSubjectId: leaderUserId ? 31 : null };
    const product = page('product-detail');
    product.onLoad({ id: 9 });
    product.onShow();
    await tick();
    assert.equal(
      (await product.onShareAppMessage().promise).path,
      '/packageMall/pages/product-detail/product-detail?id=9&referrerId=50' + expected
    );
    assert.equal((await product.onShareTimeline().promise).query, 'id=9&referrerId=50' + expected);
  });
}

test('微信分享使用同步返回值时，接收者仍可请服务端解析团长', async () => {
  signIn();
  attributionFailure = true;
  const product = page('product-detail');
  product.onLoad({ id: 9 });
  const share = product.onShareAppMessage();
  const expected = 'id=9&referrerId=50&shareUserId=50';
  assert.equal(share.path, '/packageMall/pages/product-detail/product-detail?' + expected);
  assert.equal(product.onShareTimeline().query, expected);
  assert.equal((await share.promise).path, share.path);
  signIn(51);
  const landing = new URL(share.path, 'https://example.test');
  mallLeaderShare.captureEntry({ path: landing.pathname, query: Object.fromEntries(landing.searchParams) });
  await tick();
  assert.deepEqual(bindings, [{ shareUserId: '50' }]);
});

test('兜底解析未找到有效团长时清除待处理数据', async () => {
  signIn();
  bindReply = { code: 0, data: { leader: false, leaderUserId: null, leaderSubjectId: null } };
  page('home').onLoad({ referrerId: '51', shareUserId: '51' });
  await tick();
  await mallLeaderShare.confirmPending();
  assert.deepEqual(bindings, [{ shareUserId: '51' }]);
});

test('链接同时有分享者兜底和团长参数时优先提交明确团长', async () => {
  signIn();
  page('home').onLoad({ referrerId: '51', shareUserId: '51', leaderUserId: '73' });
  await tick();
  assert.deepEqual(bindings, [{ leaderUserId: '73' }]);
});

test('游客打开后暂存独立参数，注册成功后绑定', async () => {
  const home = page('home');
  home.onLoad({ referrerId: '51', leaderUserId: '73' });
  await tick();
  assert.equal(referrer.getReferrerId(), 51);
  assert.deepEqual(bindings, []);
  await auth.registerBySms('test-register', '13000000000', '123456');
  await tick();
  assert.deepEqual(bindings, [{ leaderUserId: '73' }]);
  assert.equal(referrer.getReferrerId(), null);
});

test('已注册用户打开商品分享立即绑定', async () => {
  signIn();
  page('product-detail').onLoad({ id: '9', referrerId: '51', leaderUserId: '73' });
  await tick();
  assert.deepEqual(bindings, [{ leaderUserId: '73' }]);
});

test('冷启动可在商城页面加载前保存链接并恢复商品路由', async () => {
  const path = 'packageMall/pages/product-detail/product-detail';
  mallLeaderShare.captureEntry({ path, query: { id: '9', leaderUserId: '73' } });
  assert.deepEqual(bindings, []);
  signIn();
  await tick();
  assert.deepEqual(bindings, [{ leaderUserId: '73' }]);
  assert.equal(
    entryLogin.resolveEntryTarget({ from: path, query: 'id=9&shareUserId=73&ignore=1' }),
    '/' + path + '?id=9&shareUserId=73'
  );
});

test('打开其他团长的链接后仍转发服务端确认的原归属', async () => {
  signIn();
  attribution = { leader: false, leaderUserId: 73, leaderSubjectId: 31 };
  const home = page('home');
  home.onLoad({ referrerId: '51', leaderUserId: '99' });
  const share = await home.onShareAppMessage().promise;
  assert.equal(share.path, '/packageMall/pages/home/home?referrerId=50&leaderUserId=73');
  assert.deepEqual(bindings, [{ leaderUserId: '99' }]);
});

test('链接绑定失败后的待处理团长不会应用到另一账号', async () => {
  signIn();
  bindReply = null;
  page('home').onLoad({ leaderUserId: '73' });
  await tick();
  signIn(51);
  await tick();
  assert.deepEqual(bindings, [{ leaderUserId: '73' }]);
  bindReply = { code: 0, data: { leader: false, leaderUserId: 73, leaderSubjectId: 31 } };
  signIn();
  await tick();
  assert.deepEqual(bindings, [{ leaderUserId: '73' }, { leaderUserId: '73' }]);
});

for (const binding of [{ leaderUserId: '73' }, { shareUserId: '51' }]) {
  const description = binding.shareUserId ? '分享者兜底' : '明确团长';
  test(description + '绑定遇到网络异常时阻止创建订单，重试成功后可以下单', async () => {
    signIn();
    bindReply = null;
    page('home').onLoad(binding);
    await tick();
    const checkout = page('checkout');
    checkout.data.selectedAddress = { id: 2 };
    checkout.validateCurrentItems = async () => [{ skuId: 8, quantity: 1 }];
    await assert.rejects(checkout.createAndPay(), /团长归属/);
    assert.equal(orderPayload, undefined);
    bindReply = { code: 0, data: { leader: false, leaderUserId: 73, leaderSubjectId: 31 } };
    await assert.rejects(checkout.createAndPay(), /stop before payment/);
    assert.equal(orderPayload.addressId, 2);
    assert.equal('leaderUserId' in orderPayload, false);
    assert.equal('shareUserId' in orderPayload, false);
    assert.deepEqual(bindings, [binding, binding, binding]);
  });
}

test('服务端判为无效的团长链接不再重试且允许下单', async () => {
  signIn();
  bindReply = { code: 400, message: '团长已停用' };
  page('home').onLoad({ leaderUserId: '73' });
  await tick();
  const checkout = page('checkout');
  checkout.data.selectedAddress = { id: 2 };
  checkout.validateCurrentItems = async () => [{ skuId: 8, quantity: 1 }];
  await assert.rejects(checkout.createAndPay(), /stop before payment/);
  assert.equal(orderPayload.addressId, 2);
  assert.deepEqual(bindings, [{ leaderUserId: '73' }]);
});

test('校验报价期间收到新分享时，建单前仍确认归属', async () => {
  signIn();
  bindReply = null;
  const checkout = page('checkout');
  checkout.data.selectedAddress = { id: 2 };
  checkout.validateCurrentItems = async () => {
    page('home').onLoad({ leaderUserId: '73' });
    return [{ skuId: 8, quantity: 1 }];
  };
  await assert.rejects(checkout.createAndPay(), /团长归属/);
  assert.equal(orderPayload, undefined);
  bindReply = { code: 400 };
  signIn();
  await tick();
});
