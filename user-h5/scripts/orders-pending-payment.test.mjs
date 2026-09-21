import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const calls = [];

globalThis.getApp = () => ({ globalData: {} });
globalThis.wx = {
  showShareMenu() {},
  showToast(options) {
    calls.push({ type: 'toast', ...options });
  },
  showModal(options) {
    calls.push({ type: 'modal', ...options });
    if (options.success) options.success({ confirm: true, cancel: false });
  },
  navigateTo(options) {
    calls.push({ type: 'navigateTo', ...options });
  },
  navigateBack(options = {}) {
    calls.push({ type: 'navigateBack', ...options });
  }
};

const { orderCategories, orders } = require(path.join(root, 'data/mock.js'));
const orderStore = require(path.join(root, 'utils/orders.js'));

assert.deepEqual(
  orderCategories.map(item => item.id),
  ['all', 'store', 'stored-value', 'gift-card'],
  '订单分类必须包含礼品卡订单'
);
for (const category of orderCategories.filter(item => item.id !== 'all')) {
  assert.ok(
    orders.some(order => order.category === category.id),
    `订单数据必须覆盖分类: ${category.label}`
  );
}
assert.ok(
  orders.filter(order => order.orderStatus === 'pending_payment' && order.category === 'store').length >= 2,
  '门店订单必须包含至少两条待支付数据'
);
assert.ok(
  orders.some(order => order.orderStatus === 'pending_payment' && order.category === 'stored-value'),
  '储值订单必须包含待支付数据'
);
assert.ok(
  orders.some(order => order.orderStatus === 'pending_payment' && order.category === 'gift-card'),
  '礼品卡订单必须包含待支付数据'
);
assert.ok(
  orders.some(order => order.orderStatus === 'canceled' && order.category === 'gift-card'),
  '礼品卡订单必须包含已取消数据'
);
assert.ok(
  orders.some(order => order.orderStatus === 'pending_verify' && order.category === 'gift-card'),
  '礼品卡订单必须包含待核销数据'
);
assert.equal(orderStore.formatCountdown(244), '04:04', '待支付倒计时必须格式化为mm:ss');

orderStore.resetOrders();
const storedPending = orderStore.getOrderById('order-009');
assert.ok(storedPending && storedPending.isPendingPayment, '储值待支付订单必须可识别');
assert.equal(storedPending.statusText, '待支付', '待支付状态文案必须正确');
const storedSeconds = storedPending.remainingSeconds;
const ticked = orderStore.tickOrderCountdowns().find(order => order.id === 'order-009');
assert.equal(ticked.remainingSeconds, storedSeconds - 1, '倒计时每秒必须递减');
const canceled = orderStore.cancelOrderById('order-009');
assert.equal(canceled.statusText, '已取消', '取消订单后状态必须变为已取消');
assert.equal(canceled.isPendingPayment, false, '取消订单后不得继续显示待支付操作');

orderStore.resetOrders();
const historyGiftOrders = orderStore.filterOrders(orderStore.getOrders(), 'history', 'gift-card');
assert.ok(historyGiftOrders.length >= 2, '历史礼品卡订单必须包含待支付和已取消数据');
assert.ok(historyGiftOrders[0].isPendingPayment, '待支付订单必须排在礼品卡订单列表前面');
const giftVerifyOrders = historyGiftOrders.filter(order => order.orderStatus === 'pending_verify');
assert.equal(giftVerifyOrders.length, 1, '礼品卡待核销订单数量必须正确');
assert.equal(giftVerifyOrders[0].statusText, '待核销', '礼品卡待核销状态文案必须正确');
assert.ok(
  giftVerifyOrders[0].payTime && !historyGiftOrders.find(order => order.isPendingPayment).payTime,
  '礼品卡已支付订单必须提供支付时间且待支付订单不显示'
);
assert.ok(
  historyGiftOrders.some(order => order.cancelType === 'pending') &&
    historyGiftOrders.some(order => order.cancelType === 'paid'),
  '礼品卡已取消订单必须区分待支付取消与已支付取消'
);

function loadPage(pageRoot) {
  let definition;
  globalThis.Page = page => {
    definition = page;
  };
  delete require.cache[require.resolve(`${pageRoot}.js`)];
  require(`${pageRoot}.js`);
  return definition;
}

function createPageInstance(definition) {
  const instance = Object.assign({}, definition);
  instance.data = Object.assign({}, definition.data);
  instance.setData = function setData(updates, callback) {
    this.data = Object.assign({}, this.data, updates);
    if (typeof callback === 'function') callback();
  };
  return instance;
}

const ordersPageRoot = path.join(root, 'pages/orders/orders');
const ordersDefinition = loadPage(ordersPageRoot);
const ordersPage = createPageInstance(ordersDefinition);
ordersDefinition.onLoad.call(ordersPage);
assert.equal(ordersPage.data.activeTimeGroup, 'history', '订单页默认必须展示历史订单');
ordersDefinition.selectTimeGroup.call(ordersPage, { currentTarget: { dataset: { group: 'today' } } });
assert.equal(ordersPage.data.activeTimeGroup, 'today', '今日/历史页签必须可切换');
ordersDefinition.selectTimeGroup.call(ordersPage, { currentTarget: { dataset: { group: 'history' } } });
ordersDefinition.selectCategory.call(ordersPage, { currentTarget: { dataset: { id: 'gift-card' } } });
assert.ok(
  ordersPage.data.filteredOrders.every(order => order.category === 'gift-card'),
  '礼品卡分类必须只展示礼品卡订单'
);
const giftPendingId = ordersPage.data.filteredOrders.find(order => order.isPendingPayment).id;
ordersDefinition.cancelOrder.call(ordersPage, { currentTarget: { dataset: { id: giftPendingId } } });
assert.ok(ordersPage.data.orders.find(order => order.id === giftPendingId).isCanceled, '列表取消订单必须更新状态');
assert.ok(
  calls.some(call => call.type === 'modal'),
  '取消订单必须先弹确认框'
);
calls.length = 0;
ordersDefinition.handlePay.call(ordersPage, { currentTarget: { dataset: { id: giftPendingId } } });
assert.ok(
  calls.some(call => call.type === 'toast' && call.title === '支付功能暂未接入'),
  '立即支付必须明确提示暂未接入'
);

orderStore.resetOrders();
const detailPageRoot = path.join(root, 'pages/order-detail/order-detail');
const detailDefinition = loadPage(detailPageRoot);
const detailPage = createPageInstance(detailDefinition);
detailDefinition.onLoad.call(detailPage, { id: 'order-010' });
assert.ok(
  detailPage.data.order.isPendingPayment && detailPage.data.order.countdownText,
  '订单详情必须展示待支付倒计时'
);
detailDefinition.cancelOrder.call(detailPage, { currentTarget: { dataset: { id: 'order-010' } } });
assert.equal(detailPage.data.order.statusText, '已取消', '详情页取消后状态必须同步');

const giftPageRoot = path.join(root, 'pages/gift-card-orders/gift-card-orders');
const giftDefinition = loadPage(giftPageRoot);
const giftPage = createPageInstance(giftDefinition);
giftDefinition.onLoad.call(giftPage);
assert.ok(
  giftPage.data.statusTabs.some(tab => tab.id === 'pending_verify' && tab.label === '待核销'),
  '礼品卡订单页必须包含待核销筛选'
);
giftDefinition.selectStatus.call(giftPage, { currentTarget: { dataset: { id: 'pending_verify' } } });
assert.equal(giftPage.data.filteredOrders.length, 1, '待核销筛选必须只展示礼品卡待核销订单');
assert.equal(giftPage.data.filteredOrders[0].statusText, '待核销', '待核销筛选状态文案必须正确');
giftDefinition.selectStatus.call(giftPage, { currentTarget: { dataset: { id: 'canceled' } } });
assert.equal(giftPage.data.filteredOrders.length, 2, '已取消筛选必须同时展示待支付取消和已支付取消订单');
assert.ok(
  giftPage.data.filteredOrders.some(order => order.statusText === '待支付取消' && !order.payTime),
  '已取消筛选必须包含无支付时间的待支付取消订单'
);
assert.ok(
  giftPage.data.filteredOrders.some(order => order.statusText === '已支付取消' && order.payTime),
  '已取消筛选必须包含有支付时间的已支付取消订单'
);
assert.ok(
  giftDefinition.onShow && giftDefinition.onHide && giftDefinition.onUnload &&
    String(giftDefinition.startCountdown).includes('tickOrderCountdowns') &&
    String(giftDefinition.stopCountdown).includes('clearInterval'),
  '礼品卡订单页待支付倒计时必须使用共享倒计时定时器'
);
const giftWxml = fs.readFileSync(`${giftPageRoot}.wxml`, 'utf8');
const giftJs = fs.readFileSync(`${giftPageRoot}.js`, 'utf8');
assert.ok(
  giftWxml.includes('wx:if="{{item.isPendingPayment}}"') &&
    giftWxml.includes('class="gift-order-card__countdown"') &&
    giftWxml.indexOf('gift-order-card__countdown') < giftWxml.indexOf('class="gift-order-card__status"'),
  '待支付剩余时间必须显示在状态上方'
);
assert.ok(
  giftWxml.includes('wx:if="{{item.payTime}}"') && !giftWxml.includes('{{item.orderInfo.createdAt}}'),
  '礼品卡订单列表必须只展示支付时间'
);
assert.ok(
  giftWxml.includes('catchtap="cancelOrder"') &&
    giftWxml.includes('catchtap="handlePay"') &&
    giftWxml.includes('取消订单') &&
    giftWxml.includes('立即支付') &&
    giftJs.includes('cancelOrderById') &&
    giftJs.includes('支付功能暂未接入'),
  '待支付礼品卡订单必须提供取消和立即支付操作'
);
assert.ok(
  giftWxml.includes('gift-order-card__main') &&
    giftWxml.includes('gift-order-card__info') &&
    giftWxml.includes('订单号 {{item.orderInfo.orderNo}}') &&
    giftWxml.includes('支付时间 {{item.payTime}}'),
  '礼品卡订单卡片必须保留订单号与支付时间信息'
);
const actionsStart = giftWxml.indexOf('class="gift-order-card__actions"');
const cardClose = giftWxml.indexOf('</view>', giftWxml.indexOf('aria-label="立即支付"'));
assert.ok(
  actionsStart > giftWxml.indexOf('class="gift-order-card__main"') &&
    actionsStart < cardClose,
  '待支付操作按钮必须位于卡片内部'
);

const ordersWxml = fs.readFileSync(`${ordersPageRoot}.wxml`, 'utf8');
const ordersWxss = fs.readFileSync(`${ordersPageRoot}.wxss`, 'utf8');
const detailWxml = fs.readFileSync(`${detailPageRoot}.wxml`, 'utf8');
assert.ok(
  ordersWxml.includes('orders-time-tabs') && ordersWxml.includes('orders-categories'),
  '订单页必须包含今日/历史与分类筛选'
);
assert.ok(
  ordersWxml.includes('order-card--{{item.category}}') &&
    ordersWxml.includes("item.category === 'store'") &&
    ordersWxml.includes('store-order') &&
    ordersWxml.includes('cover-order'),
  '订单页必须分别渲染门店订单和储值/礼品卡封面订单卡片'
);
assert.ok(
  ordersWxml.includes('class="order-card__meta-list"') &&
    ordersWxml.includes('订单号 {{item.orderInfo.orderNo}}') &&
    ordersWxml.includes('wx:if="{{item.payTime}}"') &&
    ordersWxml.includes('支付时间 {{item.payTime}}'),
  '订单卡片必须显示订单号且已支付订单显示支付时间'
);
assert.ok(
  orders.filter(order => order.orderStatus !== 'pending_payment' && order.payMethod !== '未支付')
    .concat(orders.filter(order => order.orderInfo && order.orderInfo.payMethod === '未支付'))
    .length > 0,
  '订单测试数据必须覆盖已支付与未支付订单'
);
assert.ok(
  orders.filter(order => order.orderStatus !== 'pending_payment' && order.orderInfo.payMethod !== '未支付')
    .every(order => order.payTime),
  '已支付订单数据必须提供支付时间'
);
assert.ok(
  orders.filter(order => order.orderStatus === 'pending_payment' || order.orderInfo.payMethod === '未支付')
    .every(order => !order.payTime),
  '未支付订单数据不得提供支付时间'
);
assert.ok(
  ordersWxml.includes('item.previewItems.length === 1') &&
    ordersWxml.includes('store-order__single-copy') &&
    ordersWxml.includes('item.firstItem.name') &&
    ordersWxml.includes('item.firstItem.spec'),
  '单商品门店订单必须在图片右侧展示商品名称和规格'
);
assert.ok(
  ordersWxml.includes('store-order__products') && ordersWxml.includes('wx:for="{{item.previewItems}}"'),
  '多商品门店订单必须保持现有缩略图网格'
);
assert.ok(
  ordersWxml.includes('catchtap="cancelOrder"') && ordersWxml.includes('catchtap="handlePay"'),
  '订单按钮必须阻止冒泡并接通交互'
);
assert.ok(ordersWxml.includes('仅展示近一年订单'), '订单页必须保留一年订单提示');
assert.ok(
  detailWxml.includes('catchtap="cancelOrder"') && detailWxml.includes('catchtap="handlePay"'),
  '详情页待支付订单必须提供取消和支付操作'
);
assert.ok(!ordersWxml.includes('<button') && !detailWxml.includes('<button'), '订单页不得使用原生button');
assert.ok(
  ordersWxss.includes('var(--brand-green)') &&
    ordersWxss.includes('var(--brand-soft)') &&
    ordersWxss.includes('var(--radius-pill)'),
  '订单页必须遵守设计系统token'
);
const colorLiterals = [
  ...new Set(
    [...ordersWxss.matchAll(/#[0-9A-Fa-f]{3,8}/g)]
      .map(match => match[0])
      .filter(color => color.toUpperCase() !== '#FFFFFF')
  )
];
assert.deepEqual(colorLiterals, [], '订单页不得写死设计系统外颜色');
assert.ok(!/\b\d+px\b/.test(ordersWxss), '订单页不得使用px');
const ordersJs = fs.readFileSync(`${ordersPageRoot}.js`, 'utf8');
const detailJs = fs.readFileSync(`${detailPageRoot}.js`, 'utf8');
assert.ok(
  ordersJs.includes('utils/orders') && ordersJs.includes('setInterval') && ordersJs.includes('clearInterval'),
  '订单列表必须使用共享订单状态层和倒计时定时器'
);
assert.ok(
  detailJs.includes('utils/orders') && detailJs.includes('cancelOrderById'),
  '订单详情必须复用共享订单取消逻辑'
);

console.log('订单分类、待支付倒计时与三类卡片测试通过');
