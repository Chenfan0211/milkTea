import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { test, beforeEach } from 'node:test';

const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, '..');
const api = require('../utils/api.js');
const mall = require('../packageMall/utils/mall.js');
const cart = require('../utils/mall-cart.js');
const loginGuard = require('../utils/login-guard.js');
const storage = new Map();
let notices;
let navigations;
global.wx = {
  getStorageSync: key => storage.get(key) ?? '',
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  showToast: value => notices.push(value.title),
  showLoading() {},
  hideLoading() {},
  showModal: options => options.success({ confirm: true }),
  navigateTo: value => navigations.push(value.url)
};
const item = (patch = {}) => ({
  skuId: 101,
  productId: 11,
  name: '茶杯',
  quantity: 2,
  selected: true,
  listed: true,
  valid: true,
  availableStock: 10,
  salePriceFen: 1290,
  unitPrice: 1290,
  ...patch
});
const quote = (items, price = 1290) => ({
  orderable: true,
  commissionAmount: 0,
  payableAmount: items.reduce((sum, row) => sum + row.quantity * price, 0),
  items: items.map(row => ({
    skuId: row.skuId,
    quantity: row.quantity,
    valid: true,
    unitPrice: price,
    lineAmount: row.quantity * price,
    availableStock: 10
  }))
});
const deferred = () => {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
};
const flush = () => new Promise(resolve => setImmediate(resolve));

function page(name, payment = () => Promise.resolve({ paid: true }), immediateTimers = false) {
  let definition;
  const filename = path.join(root, 'packageMall/pages', name, `${name}.js`);
  const localRequire = createRequire(filename);
  vm.runInNewContext(
    fs.readFileSync(filename, 'utf8'),
    {
      require: id =>
        id.endsWith('/order-payment')
          ? { requestOrderPayment: payment }
          : id.endsWith('/subscribe-message')
            ? { requestMallShippingSubscribe: () => Promise.resolve() }
            : localRequire(id),
      Page: value => {
        definition = value;
      },
      wx: global.wx,
      setTimeout(callback) {
        if (immediateTimers) callback();
      },
      setInterval() {},
      clearInterval() {},
      console
    },
    { filename }
  );
  const instance = {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) {
      Object.assign(this.data, patch);
    }
  };
  return instance;
}
function checkout(payment) {
  const view = page('checkout', payment);
  view.clientRequestId = 'request-1';
  view.data.items = cart.read();
  view.data.selectedAddress = { id: 1 };
  view.checkoutQuote = quote(view.data.items);
  view.data.amountText = '25.80';
  return view;
}

beforeEach(() => {
  loginGuard.clearPendingAction();
  storage.clear();
  notices = [];
  navigations = [];
  storage.set('milkTea:auth:user-id', 'u1');
  storage.set('milkTea:auth:token', 'token');
  api.validateMallSkus = async items => ({
    items: items.map(row => ({
      ...row,
      productId: 11,
      productName: '茶杯',
      valid: true,
      availableStock: 10,
      salePrice: 1290
    }))
  });
  api.quoteMallCheckout = async items => quote(items);
  api.createMallOrder = async () => ({ orderNo: 'M1', totalAmount: 2580, category: 'mall', orderType: 'MALL' });
});

test('商城保留每一分并固定显示两位小数，饮品金额规则保持原样', () => {
  assert.deepEqual([0, 1, 9, 10, 101, 1290].map(mall.moneyText), ['0.00', '0.01', '0.09', '0.10', '1.01', '12.90']);
  assert.equal(api.fenToYuan(1290), 12.9);
});

test('购物车未勾选商品也按普通用户与团长身份展示对应价格', async () => {
  api.validateMallSkus = async () => ({
    items: [
      { skuId: 101, productId: 11, valid: true, availableStock: 10, salePrice: 1000, distributionCommission: 100 }
    ]
  });
  api.fetchMyRoles = async () => ({ roles: [] });
  const ordinary = page('cart');
  await ordinary.refreshValidation([item({ selected: false })]);
  assert.equal(ordinary.data.items[0].unitPriceText, '¥11.00');

  api.fetchMyRoles = async () => ({ roles: [{ roleId: 'leader', status: 'active' }] });
  const leader = page('cart');
  await leader.refreshValidation([item({ selected: false })]);
  assert.equal(leader.data.items[0].unitPriceText, '¥10.00');
});

test('未选下架商品刷新状态并保留，不影响已选商品报价和结算', async () => {
  const rows = [item(), item({ skuId: 102, selected: false })];
  cart.write(rows);
  api.validateMallSkus = async () => ({
    items: [
      { skuId: 101, valid: true, availableStock: 8, salePrice: 1290 },
      { skuId: 102, valid: false, availableStock: 0, reason: '商品已下架' }
    ]
  });
  api.quoteMallCheckout = async items => {
    assert.deepEqual(JSON.parse(JSON.stringify(items)), [{ skuId: 101, quantity: 2 }]);
    return quote(items);
  };
  const view = page('cart');
  await view.refreshValidation(rows);
  assert.equal(view.data.items[1].listed, false);
  assert.equal(cart.read()[1].availableStock, 0);
  assert.equal(view.data.amountText, '25.80');
});

test('已选无效商品阻断本次结算且失败不回填旧库存', async () => {
  const rows = [item()];
  cart.write(rows);
  api.validateMallSkus = async () => ({
    items: [{ skuId: 101, valid: false, availableStock: 1, salePrice: 1290, reason: '库存不足' }]
  });
  const view = page('cart');
  await assert.rejects(view.refreshValidation(rows), /库存不足/);
  assert.equal(view.data.items[0].availableStock, 1);
  assert.equal(cart.read()[0].availableStock, 1);
  assert.deepEqual(navigations, []);
});

test('报价失败也保留刚校验到的库存', async () => {
  const rows = [item()];
  cart.write(rows);
  api.validateMallSkus = async () => ({ items: [{ skuId: 101, valid: true, availableStock: 3, salePrice: 1290 }] });
  api.quoteMallCheckout = async () => {
    throw new Error('报价失败');
  };
  const view = page('cart');
  await assert.rejects(view.refreshValidation(rows), /报价失败/);
  assert.equal(view.data.items[0].availableStock, 3);
  assert.equal(cart.read()[0].availableStock, 3);
});

test('提交前重新报价：改价更新金额并要求再次确认，此次不建单', async () => {
  cart.write([item()]);
  const view = checkout();
  let created = 0;
  api.quoteMallCheckout = async items => quote(items, 1301);
  api.createMallOrder = async () => {
    created++;
    return { orderNo: 'M1', totalAmount: 2602 };
  };
  await assert.rejects(view.createAndPay(), /价格.*更新|价格.*变化/);
  assert.equal(view.data.amountText, '26.02');
  assert.equal(view.data.items[0].unitPriceText, '¥13.01');
  assert.equal(created, 0);
  await view.createAndPay();
  assert.equal(created, 1);
});

test('提交前订单不可合并时不建单', async () => {
  cart.write([item()]);
  const view = checkout();
  api.quoteMallCheckout = async () => ({ orderable: false, orderError: '商品来自不同供货方' });
  api.createMallOrder = async () => assert.fail('禁止创建不可合并订单');
  await assert.rejects(view.createAndPay(), /不同供货方/);
});

test('订单标签切换后，旧请求的响应及错误不能覆盖新列表', async () => {
  const old = deferred();
  const latest = deferred();
  api.fetchOrders = (n, size, options) => (options.status === 'PAID' ? latest.promise : old.promise);
  const view = page('orders');
  view.loadOrders(true);
  view.selectStatus({ currentTarget: { dataset: { status: 'PAID' } } });
  latest.resolve({ records: [{ orderNo: 'new', status: 'PAID' }], total: 1 });
  await flush();
  old.resolve({ records: [{ orderNo: 'old', status: 'CREATED' }], total: 1 });
  await flush();
  assert.equal(view.data.orders[0].orderNo, 'new');
  assert.equal(view.data.error, '');
});

test('订单重置加载中不能发起下一页请求', async () => {
  const first = deferred();
  const view = page('orders');
  let calls = 0;
  api.fetchOrders = () => {
    calls++;
    return first.promise;
  };
  view.loadOrders(true);
  view.loadMore();
  assert.equal(calls, 1);
  first.resolve({ records: [], total: 0 });
  await flush();
});

test('旧订单标签请求失败不覆盖当前标签成功状态', async () => {
  const old = deferred();
  const view = page('orders');
  api.fetchOrders = (n, size, options) =>
    options.status === 'PAID'
      ? Promise.resolve({ records: [{ orderNo: 'new', status: 'PAID' }], total: 1 })
      : old.promise;
  view.loadOrders(true);
  view.selectStatus({ currentTarget: { dataset: { status: 'PAID' } } });
  await flush();
  old.reject(new Error('旧标签失败'));
  await flush();
  assert.equal(view.data.orders[0].orderNo, 'new');
  assert.equal(view.data.error, '');
});

test('售后列表按页追加，超过50条仍可完整访问', async () => {
  api.fetchAfterSales = async (n, size) => ({
    total: 51,
    records: Array.from({ length: Math.min(size, 51 - (n - 1) * size) }, (_, index) => ({
      afterSaleNo: `A${(n - 1) * size + index + 1}`,
      refundAmount: 1
    }))
  });
  const view = page('after-sales');
  await view.loadAfterSales(true);
  await flush();
  while (view.data.hasMore) {
    await view.loadAfterSales(false);
    await flush();
  }
  assert.equal(view.data.records.length, 51);
  assert.equal(view.data.records[50].afterSaleNo, 'A51');
  assert.equal(view.data.records[50].refundAmountText, '0.01');
});

test('首次付款按结算数量清理，保留付款期间后来加购', async () => {
  cart.write([item()]);
  const payment = deferred();
  const view = checkout(() => payment.promise);
  const task = view.createAndPay();
  await flush();
  cart.add(item({ quantity: 1 }));
  payment.resolve({ paid: true });
  await task;
  assert.deepEqual(
    cart.read().map(row => row.quantity),
    [1]
  );
});

for (const name of ['orders', 'order-detail']) {
  test(`${name} 补付消费原结算快照且重复成功不再扣购物车`, async () => {
    cart.write([item()]);
    const initial = checkout(() => Promise.resolve({ paid: false }));
    await initial.createAndPay();
    cart.add(item({ quantity: 2 }));
    const view = page(name);
    const order = { orderNo: 'M1', totalAmount: 2580, payChannel: 'WXPAY' };
    view.data.order = order;
    view.data.orders = [order];
    view.loadOrder = () => {};
    view.loadOrders = () => {};
    const event = { currentTarget: { dataset: { orderNo: 'M1' } } };
    view.continuePayment(event);
    await flush();
    assert.equal(cart.read()[0].quantity, 2);
    view.continuePayment(event);
    await flush();
    assert.equal(cart.read()[0].quantity, 2);
  });
}

test('切换账号后的支付回调不能清理其他用户购物车', async () => {
  cart.write([item()]);
  const payment = deferred();
  const view = checkout(() => payment.promise);
  const task = view.createAndPay();
  await flush();
  storage.set('milkTea:auth:user-id', 'u2');
  cart.write([item({ quantity: 5 })]);
  payment.resolve({ paid: true });
  await task;
  assert.deepEqual(
    cart.read('u2').map(row => row.quantity),
    [5]
  );
  assert.equal(cart.read('u1').length, 0);
});

test('下单后删除再加购同SKU，付款不会删除新一批商品', async () => {
  cart.write([item()]);
  const payment = deferred();
  const view = checkout(() => payment.promise);
  const task = view.createAndPay();
  await flush();
  cart.remove(101);
  cart.add(item({ quantity: 3 }));
  payment.resolve({ paid: true });
  await task;
  assert.deepEqual(
    cart.read().map(row => row.quantity),
    [3]
  );
});

test('结算校验期间切换账号不会给新账号建单', async () => {
  cart.write([item()]);
  const view = checkout();
  const validation = deferred();
  api.validateMallSkus = () => validation.promise;
  let created = 0;
  api.createMallOrder = async () => {
    created++;
    return { orderNo: 'M1', totalAmount: 2580 };
  };
  const task = view.createAndPay();
  storage.set('milkTea:auth:user-id', 'u2');
  validation.resolve({ items: [{ skuId: 101, valid: true, availableStock: 10, salePrice: 1290 }] });
  await assert.rejects(task, /账号.*变化|重新结算/);
  assert.equal(created, 0);
});

test('购物车校验期间切换账号不覆盖新账号购物车', async () => {
  cart.write([item()]);
  const view = page('cart');
  const validation = deferred();
  api.validateMallSkus = () => validation.promise;
  const task = view.refreshValidation(cart.read());
  storage.set('milkTea:auth:user-id', 'u2');
  cart.write([item({ skuId: 202, quantity: 5 })]);
  validation.resolve({ items: [{ skuId: 101, valid: true, availableStock: 3, salePrice: 1290 }] });
  await task.catch(() => {});
  assert.deepEqual(
    cart.read().map(row => row.skuId),
    [202]
  );
});

test('余额首次付款与微信使用同一快照清理，保留付款期间新增数量', async () => {
  cart.write([item()]);
  const result = deferred();
  const view = checkout();
  view.data.paymentMethod = 'STORED_VALUE';
  api.payOrderByBalance = () => result.promise;
  const task = view.createAndPay();
  await flush();
  cart.add(item({ quantity: 1 }));
  result.resolve({ payStatus: 'PAID' });
  await task;
  assert.deepEqual(
    cart.read().map(row => row.quantity),
    [1]
  );
});

for (const name of ['orders', 'order-detail']) {
  test(`${name} 余额补付成功同样按快照扣量`, async () => {
    cart.write([item()]);
    await checkout(() => Promise.resolve({ paid: false })).createAndPay();
    cart.add(item({ quantity: 1 }));
    api.payOrderByBalance = async () => ({ payStatus: 'PAID' });
    const view = page(name);
    const order = { orderNo: 'M1', totalAmount: 2580, payChannel: 'STORED_VALUE' };
    view.data.order = order;
    view.data.orders = [order];
    view.loadOrder = () => {};
    view.loadOrders = () => {};
    view.continuePayment({ currentTarget: { dataset: { orderNo: 'M1' } } });
    await flush();
    assert.deepEqual(
      cart.read().map(row => row.quantity),
      [1]
    );
  });
}

test('立即购买付款成功不扣除购物车同SKU数量', async () => {
  cart.write([item()]);
  const view = checkout();
  view.data.source = 'buy';
  await view.createAndPay();
  assert.deepEqual(
    cart.read().map(row => row.quantity),
    [2]
  );
});

test('下单后减少原数量再加购，付款只消费仍保留的原商品', async () => {
  cart.write([item()]);
  const payment = deferred();
  const view = checkout(() => payment.promise);
  const task = view.createAndPay();
  await flush();
  cart.update(101, { quantity: 1 });
  cart.add(item({ quantity: 2 }));
  payment.resolve({ paid: true });
  await task;
  assert.deepEqual(
    cart.read().map(row => row.quantity),
    [2]
  );
});

test('原数组购物车可结算，升级存储后支付清理仍保持幂等', async () => {
  storage.set(cart.keyFor('u1'), [item()]);
  const view = checkout();
  await view.createAndPay();
  assert.deepEqual(cart.read(), []);
  cart.add(item({ quantity: 3 }));
  cart.settlePaidOrder('M1', 'u1');
  assert.deepEqual(
    cart.read().map(row => row.quantity),
    [3]
  );
});

test('真实 requirePhone 下改价只提示重新确认，不跳授权且授权回调不自动建单', async () => {
  storage.set('milkTea:auth:user', { phone: '13800138000' });
  cart.write([item()]);
  const view = checkout();
  api.quoteMallCheckout = async items => quote(items, 1301);
  let created = 0;
  api.createMallOrder = async () => {
    created++;
    return { orderNo: 'M1', totalAmount: 2602 };
  };
  view.submitOrder();
  await flush();
  assert.equal(view.data.amountText, '26.02');
  assert.equal(created, 0);
  assert.deepEqual(navigations, []);
  assert.ok(notices.some(title => /价格已更新.*再次提交/.test(title)));
  assert.equal(view.data.submitting, false);
  await loginGuard.flushPendingAction();
  assert.equal(created, 0, '改价错误不能留下自动续跑的建单动作');
  view.submitOrder();
  await flush();
  assert.equal(created, 1, '用户再次提交确认新金额后才建单');
});

test('真实 requirePhone 的网络业务错误只提示错误，不误触发重新授权', async () => {
  storage.set('milkTea:auth:user', { phone: '13800138000' });
  cart.write([item()]);
  const view = checkout();
  api.quoteMallCheckout = async () => {
    throw new Error('报价服务暂不可用');
  };
  view.submitOrder();
  await flush();
  assert.deepEqual(navigations, []);
  assert.ok(notices.includes('报价服务暂不可用'));
  assert.equal(await loginGuard.flushPendingAction(), null);
});

test('绑定手机号后续跑遇到改价，仍必须由用户再次确认金额', async () => {
  cart.write([item()]);
  const view = checkout();
  let created = 0;
  api.quoteMallCheckout = async items => quote(items, 1301);
  api.createMallOrder = async () => {
    created++;
    return { orderNo: 'M1', totalAmount: 2602 };
  };
  view.submitOrder();
  await flush();
  assert.equal(navigations.length, 1);
  storage.set('milkTea:auth:user', { phone: '13800138000' });
  await loginGuard.flushPendingAction();
  assert.equal(created, 0);
  assert.equal(view.data.amountText, '26.02');
  assert.ok(notices.some(title => /价格已更新/.test(title)));
  assert.equal(await loginGuard.flushPendingAction(), null);
});

test('已有三页售后数据重置时锁住加载更多，成功后从第二页继续', async () => {
  const reset = deferred();
  const requests = [];
  const view = page('after-sales');
  view.data.records = [{ afterSaleNo: 'old' }];
  view.data.page = 3;
  view.data.loading = false;
  view.data.hasMore = true;
  api.fetchAfterSales = (n, size) => {
    requests.push(n);
    return n === 1 ? reset.promise : Promise.resolve({ total: 4, records: [{ afterSaleNo: `page-${n}` }] });
  };
  const task = view.loadAfterSales(true);
  view.loadMore();
  assert.deepEqual(requests, [1]);
  reset.resolve({ total: 4, records: [{ afterSaleNo: 'page-1' }] });
  await task;
  await view.loadMore();
  assert.deepEqual(requests, [1, 2]);
  assert.deepEqual(
    Array.from(view.data.records, row => row.afterSaleNo),
    ['page-1', 'page-2']
  );
});

for (const stage of ['validate', 'quote']) {
  test(`购物车 ${stage} 回包不能复活等待期间删除的商品`, async () => {
    cart.write([item()]);
    const view = page('cart');
    view.applyItems(cart.read());
    const wait = deferred();
    if (stage === 'validate') api.validateMallSkus = () => wait.promise;
    else api.quoteMallCheckout = () => wait.promise;
    const task = view.refreshValidation(cart.read());
    await flush();
    view.removeItem({ currentTarget: { dataset: { id: 101 } } });
    wait.resolve(
      stage === 'validate'
        ? { items: [{ skuId: 101, valid: true, availableStock: 7, salePrice: 1290 }] }
        : quote([item()])
    );
    await task;
    assert.deepEqual(cart.read(), []);
    assert.equal(view.data.items.length, 0);
    assert.equal(view.data.checking, false);
  });
}

for (const action of ['increaseQuantity', 'decreaseQuantity', 'toggleSelected', 'toggleAll']) {
  test(`购物车校验回包保留用户 ${action} 的结果`, async () => {
    cart.write([item()]);
    const view = page('cart');
    view.applyItems(cart.read());
    const wait = deferred();
    api.validateMallSkus = () => wait.promise;
    const task = view.refreshValidation(cart.read());
    view[action]({ currentTarget: { dataset: { id: 101 } } });
    const expected = cart.read().map(row => [row.skuId, row.quantity, row.selected]);
    wait.resolve({ items: [{ skuId: 101, valid: true, availableStock: 7, salePrice: 1290 }] });
    await task;
    assert.deepEqual(
      cart.read().map(row => [row.skuId, row.quantity, row.selected]),
      expected
    );
    assert.deepEqual(
      Array.from(view.data.items, row => [row.skuId, row.quantity, row.selected]),
      expected
    );
    assert.equal(view.data.checking, false);
  });
}

test('付款成功进入商城订单详情后左上角返回首页，其他入口保留返回上一页', () => {
  const checkout = page('checkout', undefined, true);
  global.wx.redirectTo = ({ url }) => navigations.push(url);
  global.wx.switchTab = ({ url }) => navigations.push(url);
  checkout.finishPayment({ orderNo: 'M1' }, { paid: true }, 'u1');
  assert.equal(navigations[0], '/packageMall/pages/order-detail/order-detail?orderNo=M1&fromPayment=1');

  page('checkout', undefined, true).finishPayment({ orderNo: 'M2' }, { paid: false }, 'u1');
  assert.equal(navigations[1], '/packageMall/pages/order-detail/order-detail?orderNo=M2');

  const paidDetail = page('order-detail');
  paidDetail.loadOrder = () => {};
  paidDetail.onLoad({ orderNo: 'M1', fromPayment: '1' });
  assert.equal(paidDetail.data.fromPayment, true);
  paidDetail.handleBack();
  assert.equal(navigations[2], '/pages/home/home');

  const regularDetail = page('order-detail');
  regularDetail.loadOrder = () => {};
  regularDetail.onLoad({ orderNo: 'M2' });
  assert.equal(regularDetail.data.fromPayment, false);
  regularDetail.handleBack();
  assert.equal(navigations.length, 3, '其他入口的返回行为应交给导航栏处理');

  const detailWxml = fs.readFileSync(path.join(root, 'packageMall/pages/order-detail/order-detail.wxml'), 'utf8');
  assert.match(detailWxml, /delta="\{\{fromPayment \? 0 : 1\}\}"[^>]*bind:back="handleBack"/);
  assert.doesNotMatch(detailWxml, /查看售后记录|查看全部商城订单/);
});
