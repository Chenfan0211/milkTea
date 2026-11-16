import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { beforeEach, test } from 'node:test';

const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, '..');
const api = require('../utils/api.js');
const cart = require('../utils/mall-cart.js');
const mall = require('../packageMall/utils/mall.js');
const profile = require('../utils/user-profile.js');
const storage = new Map();
let notices;
let timers;
let redirects;
const flush = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => {
  let resolve;
  const promise = new Promise(yes => {
    resolve = yes;
  });
  return { promise, resolve };
};
global.wx = {
  getStorageSync: key => storage.get(key) ?? '',
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  showToast: options => notices.push(options.title),
  showLoading() {},
  hideLoading() {},
  redirectTo: options => redirects.push(options.url)
};

function page(name, wxPayment = () => assert.fail('储值订单不能调用微信支付'), base = 'packageMall/pages') {
  let definition;
  const filename = path.join(root, base, name, `${name}.js`);
  const localRequire = createRequire(filename);
  vm.runInNewContext(
    fs.readFileSync(filename, 'utf8'),
    {
      require: id => (id.endsWith('/order-payment') ? { requestOrderPayment: wxPayment } : localRequire(id)),
      Page: value => {
        definition = value;
      },
      wx: global.wx,
      setTimeout: callback => timers.push(callback),
      setInterval() {},
      clearInterval() {}
    },
    { filename }
  );
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) {
      Object.assign(this.data, patch);
    }
  };
}

function checkout() {
  cart.write([
    {
      skuId: 101,
      productId: 11,
      name: '测试茶杯',
      quantity: 1,
      selected: true,
      listed: true,
      availableStock: 10,
      salePriceFen: 1,
      unitPrice: 1
    }
  ]);
  const view = page('checkout');
  view.clientRequestId = 'stored-checkout-1';
  view.data.items = cart.read();
  view.data.selectedAddress = { id: 1 };
  view.data.paymentMethod = 'STORED_VALUE';
  view.checkoutQuote = { payableAmount: 1 };
  return view;
}

beforeEach(() => {
  storage.clear();
  notices = [];
  timers = [];
  redirects = [];
  storage.set('milkTea:auth:user-id', 'u1');
  storage.set('milkTea:auth:token', 'test-token');
  api.validateMallSkus = async items => ({
    items: items.map(item => ({
      ...item,
      valid: true,
      productId: 11,
      productName: '测试茶杯',
      availableStock: 10,
      salePrice: 1
    }))
  });
  api.quoteMallCheckout = async items => ({
    orderable: true,
    payableAmount: 1,
    commissionAmount: 0,
    items: items.map(item => ({ ...item, valid: true, unitPrice: 1, lineAmount: 1 }))
  });
  api.createMallOrder = async request => ({
    orderNo: 'M1',
    category: 'mall',
    orderType: 'MALL',
    status: 'CREATED',
    payStatus: 'UNPAID',
    totalAmount: 1,
    payChannel: request.payChannel
  });
});

test('结算储值余额不足保留业务原因、待支付订单及购物车', async () => {
  api.payOrderByBalance = async () => {
    const error = new Error('储值余额不足，请充值后重试');
    error.res = { code: 400, message: error.message, data: null };
    throw error;
  };
  await checkout().createAndPay();
  assert.ok(
    notices.some(title => /余额不足/.test(title)),
    '不能把余额不足吞成笼统继续支付'
  );
  assert.equal(cart.read()[0].quantity, 1);
  timers.forEach(callback => callback());
  assert.deepEqual(redirects, ['/packageMall/pages/order-detail/order-detail?orderNo=M1']);
});

test('已建单遇到内部错误只显示恢复提示，不泄露内部异常', async () => {
  api.payOrderByBalance = async () => {
    const error = new Error('SQLException internal diagnostic');
    error.res = { code: 500, message: error.message, data: null };
    throw error;
  };
  await checkout().createAndPay();
  assert.deepEqual(notices, ['订单已创建，请继续支付']);
  assert.equal(cart.read()[0].quantity, 1);
  timers.forEach(callback => callback());
  assert.equal(redirects.length, 1);
});

test('建单请求等待时切换选项，支付仍使用创建订单的储值渠道', async () => {
  const created = deferred();
  api.createMallOrder = request => {
    assert.equal(request.payChannel, 'STORED_VALUE');
    return created.promise;
  };
  api.payOrderByBalance = async orderNo => {
    assert.equal(orderNo, 'M1');
    return { payStatus: 'PAID', status: 'PAID' };
  };
  const view = checkout();
  const task = view.createAndPay();
  await flush();
  view.selectPayment({ currentTarget: { dataset: { method: 'WXPAY' } } });
  created.resolve({ orderNo: 'M1', totalAmount: 1, payChannel: 'STORED_VALUE' });
  await task;
  assert.ok(notices.includes('支付成功'), '储值建单后不能因页面选项变化转入微信支付');
  assert.deepEqual(cart.read(), []);
});

test('储值首次付款服务端未确认已付时保留购物车', async () => {
  api.payOrderByBalance = async () => ({ payStatus: 'UNPAID', status: 'CREATED' });
  await checkout().createAndPay();
  assert.equal(cart.read()[0].quantity, 1);
  assert.ok(notices.includes('订单待支付'));
});

test('一分钱储值实付成功才清理购物车，页面金额不把分误当元', async () => {
  api.payOrderByBalance = async () => ({ payStatus: 'PAID', status: 'PAID' });
  const view = checkout();
  await view.createAndPay();
  assert.equal(view.data.amountText, '0.01');
  assert.deepEqual(cart.read(), []);
  assert.ok(notices.includes('支付成功'));
});

for (const name of ['orders', 'order-detail']) {
  test(`${name} 储值补付余额不足释放操作锁并保留购物车`, async () => {
    const checkoutView = checkout();
    api.payOrderByBalance = async () => ({ payStatus: 'UNPAID' });
    await checkoutView.createAndPay();
    notices = [];
    api.payOrderByBalance = async () => {
      throw new Error('储值余额不足');
    };
    const view = page(name);
    const order = { orderNo: 'M1', totalAmount: 1, status: 'CREATED', payStatus: 'UNPAID', payChannel: 'STORED_VALUE' };
    view.data.order = order;
    view.data.orders = [order];
    view.continuePayment({ currentTarget: { dataset: { orderNo: 'M1' } } });
    await flush();
    assert.ok(notices.includes('储值余额不足'));
    assert.equal(Boolean(view.data.actionBusy || view.data.actionOrderNo), false);
    assert.equal(cart.read()[0].quantity, 1);
  });

  test(`${name} 储值补付双击只发一次，成功后按结算快照幂等清理`, async () => {
    api.payOrderByBalance = async () => ({ payStatus: 'UNPAID' });
    await checkout().createAndPay();
    cart.add({ ...cart.read()[0], quantity: 2 });
    const paid = deferred();
    let requests = 0;
    api.payOrderByBalance = () => {
      requests++;
      return paid.promise;
    };
    const view = page(name);
    const order = { orderNo: 'M1', totalAmount: 1, payChannel: 'STORED_VALUE' };
    view.data.order = order;
    view.data.orders = [order];
    view.loadOrder = () => {};
    view.loadOrders = () => {};
    const event = { currentTarget: { dataset: { orderNo: 'M1' } } };
    view.continuePayment(event);
    view.continuePayment(event);
    assert.equal(requests, 1);
    paid.resolve({ payStatus: 'PAID', status: 'PAID' });
    await flush();
    assert.equal(cart.read()[0].quantity, 2);
    cart.settlePaidOrder('M1', 'u1');
    assert.equal(cart.read()[0].quantity, 2);
  });
}

test('订单详情返回页面重新拉取退款状态，仍显示原储值支付渠道', async () => {
  let status = 'PAID';
  api.fetchOrderDetail = async () => ({
    orderNo: 'M1',
    category: 'mall',
    orderType: 'MALL',
    status,
    payStatus: 'PAID',
    payChannel: 'STORED_VALUE',
    totalAmount: 1,
    refundStatus: status === 'REFUNDED' ? 'SUCCESS' : null
  });
  const view = page('order-detail');
  view.data.orderNo = 'M1';
  view.loadOrder();
  await flush();
  assert.equal(view.data.order.status, 'PAID');
  status = 'REFUNDED';
  view.onShow();
  await flush();
  assert.equal(view.data.order.statusText, '已退款');
  assert.equal(view.data.order.payMethodText, '余额支付');
  assert.equal(view.data.order.canPay, false);
});

test('售后详情返回页面重新拉取到账状态并保留分单位退款金额', async () => {
  let status = 'REFUNDING';
  api.fetchAfterSaleDetail = async () => ({
    afterSaleNo: 'AS1',
    orderNo: 'M1',
    status,
    refundAmount: 1,
    completeTime: status === 'SUCCESS' ? '2026-11-03 12:00:00' : null,
    items: []
  });
  const view = page('after-sale-detail');
  view.data.afterSaleNo = 'AS1';
  view.loadAfterSale();
  await flush();
  assert.equal(view.data.record.statusText, '退款中');
  status = 'SUCCESS';
  view.onShow();
  await flush();
  assert.equal(view.data.record.statusText, '退款成功');
  assert.equal(view.data.record.refundAmountText, '0.01');
  assert.equal(view.data.record.completeTimeText, '2026-11-03 12:00:00');
});

test('历史微信退款订单保持微信支付标签，不能改成储值', () => {
  const order = mall.decorateOrder({ status: 'REFUNDED', payStatus: 'PAID', payChannel: 'WXPAY', totalAmount: 1 });
  assert.equal(order.payMethodText, '微信支付');
  assert.equal(order.canPay, false);
});

function balancePage() {
  const view = page('stored-value', undefined, 'pages');
  view.syncStore = () => {};
  view.loadPackages = () => {};
  return view;
}

test('储值页每次显示拉取远端余额，商城扣款及退款后不沿用旧缓存', async () => {
  profile.saveUserProfile({ balance: 0.02 });
  let balanceFen = 1;
  api.fetchUserProfile = async () => ({ id: 'u1', balance: balanceFen, points: 0, totalSpend: 0 });
  const view = balancePage();
  view.onShow();
  await flush();
  assert.equal(view.data.balanceText, '0.01');
  assert.equal(profile.getUserProfile().balance, 0.01);
  balanceFen = 2;
  view.onShow();
  await flush();
  assert.equal(view.data.balanceText, '0.02');
  assert.equal(profile.getUserProfile().balance, 0.02);
});

test('储值页首次加载及连续显示共用同一未完成余额请求', async () => {
  const result = deferred();
  let requests = 0;
  api.fetchUserProfile = () => {
    requests++;
    return result.promise;
  };
  const view = balancePage();
  view.onLoad();
  view.onShow();
  view.onShow();
  assert.equal(requests, 1);
  result.resolve({ id: 'u1', balance: 101, points: 0, totalSpend: 0 });
  await flush();
  assert.equal(view.data.balanceText, '1.01');
});

test('储值页拉余额期间切换账号，旧回包不能覆盖新用户缓存与展示', async () => {
  const result = deferred();
  api.fetchUserProfile = () => result.promise;
  const view = balancePage();
  const task = view.syncBalance(true);
  storage.set('milkTea:auth:user-id', 'u2');
  profile.saveUserProfile({ balance: 9.99 });
  view.syncBalance();
  result.resolve({ id: 'u1', balance: 1, points: 0, totalSpend: 0 });
  await task;
  assert.equal(profile.getUserProfile().balance, 9.99);
  assert.equal(view.data.balanceText, '9.99');
});

test('储值页刷新失败保留已知余额，不显示虚假的零余额', async () => {
  profile.saveUserProfile({ balance: 3.21 });
  api.fetchUserProfile = async () => {
    throw new Error('暂时断网');
  };
  const view = balancePage();
  view.onShow();
  await flush();
  assert.equal(view.data.balanceText, '3.21');
  assert.equal(profile.getUserProfile().balance, 3.21);
});

test('游客打开储值页不请求受保护的余额接口', async () => {
  storage.delete('milkTea:auth:token');
  storage.delete('milkTea:auth:user-id');
  api.fetchUserProfile = async () => assert.fail('游客不应拉取账户余额');
  const view = balancePage();
  view.onShow();
  await flush();
  assert.equal(view.data.balanceText, '0.00');
});
