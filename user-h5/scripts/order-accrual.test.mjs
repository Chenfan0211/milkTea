import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = {};
const calls = [];
const apiCalls = [];
let definition;

globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    calls.push(options);
  },
  showLoading() {},
  hideLoading() {},
  navigateBack() {},
  navigateTo() {},
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 })
};
globalThis.getApp = () => ({ globalData: {} });
globalThis.Page = page => {
  definition = page;
};

// 拦截 api：余额支付必须走后端（createOrder -> payOrderByBalance），
// 不能再让页面自己改本地余额。
const api = require(path.join(root, 'utils/api.js'));
api.createOrder = payload => {
  apiCalls.push({ fn: 'createOrder', payload });
  return Promise.resolve({ orderNo: 'WX-TEST-1', paidAmount: 2580 });
};
api.payOrderByBalance = orderNo => {
  apiCalls.push({ fn: 'payOrderByBalance', orderNo });
  return Promise.resolve({ orderNo, status: 'PAID' });
};
api.fetchUserProfile = () => {
  apiCalls.push({ fn: 'fetchUserProfile' });
  // 与真实接口一致：后端 balance 单位是「分」，由 normalizeRemoteProfile 换算成元
  return Promise.resolve({ balance: 74100, points: 25 });
};

require(path.join(root, 'pages/order-confirm/order-confirm.js'));
const { getUserProfile, saveUserProfile } = require(path.join(root, 'utils/user-profile.js'));

function createPage() {
  const instance = Object.assign({}, definition);
  instance.data = JSON.parse(JSON.stringify(definition.data));
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  return instance;
}

// 储值支付成功：实付金额同时累计成长值与发放时光币
//
// 金额口径（与 product-card / spec-sheet / 后端保持一致）：
//   会员价 = 商品原价(originalPrice) × 等级折扣
//   储值到手价 = 会员价 - 储值立减(storedValuePrice)
// 注意 storedValuePrice 是「立减金额」而非「储值价」——
// 见 product-service 的 Product 实体注释，以及 spec-sheet.js:106-107。
//
// 历史问题：本用例原先把 storedValuePrice 当成储值价（且没给 originalPrice），
// 与全链路口径不符；确认页改为统一走 calcMemberPrice 后暴露出来。
// ============ 新口径：余额支付必须走后端，页面不再本地扣款 ============
//
// 原实现的三个问题（本用例现在防的就是它们回归）：
//   1. 不建订单、不写支付记录 -> 后台「支付记录」看不到这笔消费；
//   2. 只在本地存储扣余额 -> 换设备余额"复原"，无法对账；
//   3. 前端本地判断余额 -> 篡改本地存储即可超支。
//
// 现在：createOrder 建单 -> payOrderByBalance 由服务端原子扣款并置已支付，
// 前端只把后端返回的余额同步到本地展示。

saveUserProfile({ totalSpend: 0, points: 0, balance: 999 });
apiCalls.length = 0;
const page = createPage();
page.data.store = { id: 101 };
page.data.items = [{ productId: 'classic-001', price: 12.9, originalPrice: 12.9, storedValuePrice: 0, quantity: 2, name: '测试饮品' }];
page.data.paymentMethod = 'stored-value';
definition.submitWithStoredValue.call(page);

// 异步链路：先断言已发起「建单 + 余额支付」，再等资料回读完成
assert.ok(
  apiCalls.some(item => item.fn === 'createOrder'),
  '余额支付必须先在后端建立订单（否则不产生支付记录）'
);
const placeOrder = apiCalls.find(item => item.fn === 'createOrder');
assert.equal(placeOrder.payload.items[0].quantity, 2, '下单必须带上购物车件数');
assert.equal(
  placeOrder.payload.clientAmount,
  null,
  '储值支付有额外立减，与后端会员价口径不同，不得传 clientAmount'
);

// 等异步链路（createOrder -> payOrderByBalance -> fetchUserProfile）跑完
await new Promise(resolve => setTimeout(resolve, 0));

assert.ok(
  apiCalls.some(item => item.fn === 'payOrderByBalance' && item.orderNo === 'WX-TEST-1'),
  '必须调用服务端余额支付接口，而不是本地改余额'
);
assert.ok(
  apiCalls.some(item => item.fn === 'fetchUserProfile'),
  '支付后必须回读服务端资料刷新余额展示'
);

// 本地余额必须来自服务端回读，而不是本地相减
const after = getUserProfile();
assert.equal(after.balance, 741, '本地余额必须以后端返回为准（74100 分 -> 741 元）');
assert.equal(after.points, 25, '时光币以服务端口径回读');

const source = require('fs').readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
assert.ok(
  !source.includes('nextBalance'),
  '页面不得再本地计算余额（nextBalance 必须已移除，否则余额可被绕过）'
);
assert.ok(
  source.includes('payOrderByBalance'),
  '页面必须调用服务端余额支付接口'
);

console.log('储值余额支付走后端（建单 + 服务端扣款 + 状态回读）测试通过');