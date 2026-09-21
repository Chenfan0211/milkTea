import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const storage = {};
const toasts = [];
let modalOptions = null;
let navigateBackCount = 0;

globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    toasts.push(options);
  },
  showModal(options) {
    modalOptions = options;
  },
  navigateBack() {
    navigateBackCount += 1;
  },
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  getWindowInfo: () => ({ statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ height: 32, top: 26 })
};
const mockApp = {
  globalData: {
    points: 1000,
    pointsRecords: [],
    exchangeRecords: [],
    exchangeVerifyPool: []
  }
};
globalThis.getApp = () => mockApp;

const { getPoints, setPoints, exchangeProduct } = require(path.join(root, 'utils/points.js'));

// 余额不足不扣
const insufficient = exchangeProduct({
  product: { points: 500.6, name: '测试商品' },
  quantity: 3,
  currentPoints: 100,
  pointsRecords: [],
  exchangeRecords: []
});
assert.equal(insufficient.ok, false, '余额不足必须返回失败');
assert.equal(insufficient.points, 100, '余额不足时余额不变');

// 成功扣减并写入记录
const result = exchangeProduct({
  product: { points: 20, name: '五零时光公益宠粮' },
  quantity: 2,
  currentPoints: 100,
  pointsRecords: [],
  exchangeRecords: []
});
assert.equal(result.ok, true, '余额充足必须成功');
assert.equal(result.cost, 40, '扣减金额必须等于单价乘数量');
assert.equal(result.points, 60, '兑换后余额必须正确');
assert.equal(result.pointsRecord.amount, '-40', '明细必须为负值');
assert.equal(result.pointsRecord.title, '五零时光公益宠粮', '明细标题必须为商品名');
assert.equal(result.pointsRecord.source, '时光币兑换', '明细来源必须为时光币兑换');
assert.equal(result.exchangeRecord.status, 'pending_verify', '兑换记录状态必须为待核销');
assert.ok(result.pickupCode && /^CZ\d{14}$/.test(result.pickupCode), '兑换必须生成长订单号风格自提码');

// 页面脚本可加载
let definition;
globalThis.Page = page => {
  definition = page;
};
assert.doesNotThrow(() => {
  delete require.cache[require.resolve(path.join(root, 'pages/points-exchange/points-exchange.js'))];
  require(path.join(root, 'pages/points-exchange/points-exchange.js'));
}, '兑换详情页脚本必须能正常加载');
assert.ok(definition && definition.data, '兑换详情页必须注册 Page');

const js = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.js'), 'utf8');
assert.ok(js.includes('wx.showModal'), '兑换必须弹二次确认');
assert.ok(js.includes('确认兑换'), '二次确认必须包含确认文案');
assert.ok(js.includes('exchangeProduct'), '兑换必须调用 exchangeProduct');
assert.ok(js.includes('getPoints'), '兑换必须用 getPoints 读取余额');

// 商城余额改用 getPoints
const pointsMallJs = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.js'), 'utf8');
assert.ok(pointsMallJs.includes('getPoints()'), '商城余额必须用 getPoints 同步');

// app.js 初始化 exchangeRecords
const appJs = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
assert.ok(appJs.includes('exchangeRecords: exchangeRecords.map'), 'app.js 必须初始化 exchangeRecords');

// 兑换核销池与 verifyExchange
const { verifyExchange } = require(path.join(root, 'utils/points.js'));
const code = result.pickupCode;
const verifyOk = verifyExchange(code);
assert.equal(verifyOk.ok, true, '兑换自提码必须可核销');
assert.equal(verifyOk.entry.verified, true, '核销后必须标记已核销');
const verifyDup = verifyExchange(code);
assert.equal(verifyDup.ok, false, '重复核销必须失败');
assert.equal(verifyDup.reason, 'already_verified', '重复核销原因必须为已核销');
assert.equal(verifyExchange('NOTEXIST').ok, false, '不存在的码必须核销失败');

// 兑换记录页含待核销页签
const mockSource = fs.readFileSync(path.join(root, 'data/mock.js'), 'utf8');
assert.ok(mockSource.includes("id: 'pending_verify'"), '兑换记录分类必须含待核销');

console.log('时光币兑换：二次确认、余额校验、扣减与记录写入测试通过');
