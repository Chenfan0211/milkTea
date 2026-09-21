import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = {};
const calls = [];
let definition;

globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    calls.push(options);
  },
  navigateBack() {},
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
saveUserProfile({ totalSpend: 0, points: 0, balance: 999 });
const page = createPage();
page.data.items = [{ price: 12.9, storedValuePrice: 12.9, quantity: 2, name: '测试饮品' }];
page.data.paymentMethod = 'stored-value';
definition.submitWithStoredValue.call(page);

const after = getUserProfile();
const expectedAmount = Math.round(12.9 * 2 * 10) / 10;
assert.equal(after.totalSpend, expectedAmount, '储值支付成功后累计实付金额必须计入成长值');
assert.equal(after.points, Math.floor(expectedAmount), '储值支付成功后必须按每 1 元 1 时光币发放');

// 二次下单必须累加而不是覆盖
const page2 = createPage();
page2.data.items = [{ price: 8, storedValuePrice: 8, quantity: 1, name: '测试饮品' }];
page2.data.paymentMethod = 'stored-value';
definition.submitWithStoredValue.call(page2);
const after2 = getUserProfile();
assert.equal(after2.totalSpend, Math.round((expectedAmount + 8) * 10) / 10, '多次下单必须累加成长值');
assert.equal(after2.points, Math.floor(expectedAmount) + 8, '多次下单必须累加时光币');

// 余额不足不得累计（默认余额 999，超过即触发拦截）
saveUserProfile({ totalSpend: 5, points: 5 });
const page3 = createPage();
page3.data.items = [{ price: 1200, storedValuePrice: 1200, quantity: 1, name: '测试饮品' }];
page3.data.paymentMethod = 'stored-value';
definition.submitWithStoredValue.call(page3);
const after3 = getUserProfile();
assert.equal(after3.totalSpend, 5, '余额不足不得增加成长值');
assert.equal(after3.points, 5, '余额不足不得发放时光币');
assert.ok(
  calls.some(item => item.title === '余额不足，请先充值'),
  '余额不足必须给出提示'
);

const source = require('fs').readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
assert.ok(source.includes('totalSpend') && source.includes('points: nextPoints'), '订单确认页必须写回成长值与时光币');

console.log('下单实付金额累计成长值与发放时光币测试通过');
