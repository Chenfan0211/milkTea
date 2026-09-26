import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const calls = [];
const storage = {};
const giftHistoryState = {
  cards: [],
  orders: [],
  denominations: [],
  denominationCalls: 0
};

globalThis.getApp = () => ({ globalData: {} });

// 远端订单镜像：取消订单后由 refreshOrdersFromRemote 重新读取，
// 用于验证「取消会真正调后端并在刷新后反映新状态」。
const remoteOrders = [];
const remoteCanceled = new Set();

globalThis.wx = {
  showShareMenu() {},
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  removeStorageSync(key) {
    delete storage[key];
  },
  showLoading() {},
  hideLoading() {},
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
  },
  request(options) {
    const url = String(options.url || '');
    calls.push({ type: 'request', method: options.method || 'GET', url });
    const succeed = body => {
      if (options.success) options.success({ statusCode: 200, data: body });
    };
    // 取消订单接口：记录到远端已取消集合，供随后的列表刷新读取
    if (url.endsWith('/cancel')) {
      const orderNo = decodeURIComponent(url.split('/orders/')[1].replace('/cancel', ''));
      remoteCanceled.add(orderNo);
      succeed({ code: 200, data: { orderNo, status: 'CANCELED' } });
      return;
    }
    // 订单列表接口：返回远端镜像（已取消的订单标为 CANCELED）
    if (/\/api\/v1\/app\/orders(\?|$)/.test(url)) {
      const records = remoteOrders.map(order => {
        const orderNo = order.orderNo || (order.orderInfo && order.orderInfo.orderNo);
        return remoteCanceled.has(orderNo) ? { ...order, status: 'CANCELED', orderStatus: 'canceled' } : order;
      });
      succeed({ code: 200, data: { records, total: records.length } });
      return;
    }
    if (url.split('?')[0].endsWith('/api/v1/app/gift-cards/denominations')) {
      giftHistoryState.denominationCalls += 1;
      succeed({ code: 200, data: giftHistoryState.denominations });
      return;
    }
    if (url.split('?')[0].endsWith('/api/v1/app/gift-cards/orders')) {
      succeed({
        code: 200,
        data: { records: giftHistoryState.orders, total: giftHistoryState.orders.length }
      });
      return;
    }
    if (url.split('?')[0].endsWith('/api/v1/app/gift-cards')) {
      succeed({ code: 200, data: giftHistoryState.cards });
      return;
    }
    succeed({ code: 200, data: null });
  }
};

// 订单不再是本地假数据（由后端 /api/v1/app/orders 提供）。
// 这里用一组「测试夹具」验证订单状态机：待支付倒计时 / 取消 / 核销 / 排序。
const { orderCategories } = require(path.join(root, 'data/mock.js'));
const orderStore = require(path.join(root, 'utils/orders.js'));
const { refreshOrdersFromRemote } = orderStore;

assert.deepEqual(
  orderCategories.map(item => item.id),
  ['all', 'store', 'stored-value', 'gift-card'],
  '订单分类必须包含礼品卡订单'
);
assert.equal(typeof refreshOrdersFromRemote, 'function', '订单必须支持从后端刷新');
assert.equal(orderStore.formatCountdown(244), '04:04', '待支付倒计时必须格式化为mm:ss');
// 测试夹具：与后端返回结构一致的订单（注入到 orderStore 的本地镜像）
function makeOrder(overrides) {
  return Object.assign({
    category: 'gift-card',
    orderStatus: 'pending_verify',
    status: '待核销',
    timeGroup: 'history',
    // 后端订单金额以「分」下发（OrderDTO.totalAmount 等），前端统一换算为「元」展示
    totalAmount: 2000,
    discountAmount: 0,
    pickupCode: 'CZ20260101000001',
    payTime: '2026-09-17 10:00:00',
    items: [{ id: 'i1', name: '五零时光礼品卡', spec: '100元', unitPrice: 10000, originalPrice: 10000, quantity: 1 }],
    orderInfo: { orderNo: 'T20260917001', createdAt: '2026-09-17 09:00:00', payMethod: '微信支付' }
  }, overrides || {});
}

const FIXTURES = [
  makeOrder({ id: 'order-009', category: 'stored-value', timeGroup: 'today', orderStatus: 'pending_payment', remainingSeconds: 300, status: '待支付', payTime: '', orderInfo: { orderNo: 'T-ST-009', createdAt: '2026-09-17 09:00:00', payMethod: '未支付' } }),
  makeOrder({ id: 'order-010', category: 'store', timeGroup: 'today', orderStatus: 'pending_payment', remainingSeconds: 244, status: '待支付', payTime: '', orderInfo: { orderNo: 'T-ST-010', createdAt: '2026-09-17 09:10:00', payMethod: '未支付' } }),
  makeOrder({ id: 'order-011' }),
  makeOrder({ id: 'order-014', category: 'gift-card', timeGroup: 'history', orderStatus: 'pending_payment', remainingSeconds: 200, status: '待支付', payTime: '', orderInfo: { orderNo: 'T-GC-014', createdAt: '2026-09-17 09:20:00', payMethod: '未支付' } }),
  makeOrder({ id: 'order-013', orderStatus: 'canceled', status: '已取消', cancelType: 'paid', refundAmount: 10000 })
];

// 幂等性：decorateOrder 被重复调用时金额不得被反复除以 100
{
  orderStore.setOrdersForTest([{ id: 'order-idem', orderNo: 'T-I', totalAmount: 2560, items: [{ productId: 'p1', name: '抹茶', unitPrice: 1280, quantity: 2 }] }]);
  const first = orderStore.getOrderById('order-idem');
  const second = orderStore.getOrderById('order-idem');
  const third = orderStore.getOrderById('order-idem');
  assert.equal(first.amountText, '25.6', '订单金额必须按分转元');
  assert.equal(second.amountText, '25.6', '二次读取订单金额必须保持幂等');
  assert.equal(third.amountText, '25.6', '多次读取订单金额必须保持幂等');
  assert.equal(third.items[0].unitPrice, 12.8, '多次读取条目单价必须保持幂等');
  assert.ok(third.orderInfo.orderNo === 'T-I', '归一化字段必须可重复读取');
}
// 异常输入不得崩溃
{
  orderStore.setOrdersForTest([null, { id: 'order-null-items', items: null }]);
  assert.doesNotThrow(() => orderStore.getOrders(), '缺失字段或 null 订单不得导致崩溃');
}

// 后端扁平字段 -> 前端结构 的适配（createTime/orderNo/payStatus/store）
{
  orderStore.setOrdersForTest([{
    id: 'order-flat', orderNo: 'T20260917001', store: '五零时光·五一广场店',
    status: '待核销', payStatus: '已支付', createTime: '2026-09-17 09:00:00',
    totalAmount: 2000, category: undefined, items: [{ productId: 'p1', name: '抹茶', unitPrice: 2000, quantity: 1 }]
  }]);
  const decorated = orderStore.getOrderById('order-flat');
  assert.ok(decorated.orderInfo && decorated.orderInfo.orderNo === 'T20260917001', '后端扁平 orderNo 必须映射进 orderInfo.orderNo');
  assert.ok(decorated.orderInfo.createdAt === '2026-09-17 09:00:00', '后端扁平 createTime 必须映射进 orderInfo.createdAt');
  assert.ok(decorated.createdAtText, '订单创建时间文案必须可生成');
  assert.equal(decorated.storeName, '五零时光·五一广场店', '后端扁平 store 必须映射为 storeName');
  assert.equal(decorated.category, 'store', '未显式提供 category 时必须按门店订单兜底');
  assert.equal(decorated.title, '五零时光·五一广场店', '订单标题必须优先使用门店名');
  assert.ok(decorated.items[0].id, '订单条目必须补齐渲染所需 id，避免 wx:key 警告');
}

// 分页契约：默认 20 条/页，三接口统一返回 PageResult，前端支持上拉加载
{
  const apiJs = fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8');
  const ordersJs = fs.readFileSync(path.join(root, 'utils/orders.js'), 'utf8');
  const pageJs = fs.readFileSync(path.join(root, 'pages/orders/orders.js'), 'utf8');
  assert.ok(
    /function fetchOrders\(page = 1, size = 20\)/.test(apiJs) &&
      /function fetchStoredValueOrders\(page = 1, size = 20\)/.test(apiJs) &&
      /function fetchGiftCardOrders\(page = 1, size = 20\)/.test(apiJs),
    '三个订单接口必须统一支持 page/size 且默认 20 条'
  );
  assert.ok(ordersJs.includes('ORDER_PAGE_SIZE = 20'), '订单分页大小必须为 20');
  assert.ok(ordersJs.includes('pickRecords'), '必须兼容 PageResult.records 结构');
  assert.ok(
    pageJs.includes('onReachBottom') && pageJs.includes('append: true'),
    '订单页必须支持上拉加载更多'
  );
}
{
  const { pickRecords, ORDER_PAGE_SIZE } = require(path.join(root, 'utils/orders.js'));
  assert.equal(ORDER_PAGE_SIZE, 20, '订单分页大小必须为 20');
  assert.deepEqual(pickRecords({ records: [1, 2], total: 3 }), [1, 2], '必须能从 PageResult 取 records');
  assert.deepEqual(pickRecords([1, 2]), [1, 2], '必须兼容裸数组返回');
  assert.deepEqual(pickRecords(null), [], '异常返回必须兜底为空数组');
}

// 三类订单数据层：支持按当前页签与分类请求，并按来源打 category 标
{
  const ordersPageJs = fs.readFileSync(path.join(root, 'pages/orders/orders.js'), 'utf8');
  const ordersUtilJs = fs.readFileSync(path.join(root, 'utils/orders.js'), 'utf8');
  assert.ok(
    ordersUtilJs.includes('options.timeGroup') && ordersUtilJs.includes('options.category'),
    '订单数据层必须支持按当前页签和分类请求'
  );
  assert.ok(
    ordersPageJs.includes('onShow') && ordersPageJs.includes('loadOrders') && ordersPageJs.includes('refreshOrdersFromRemote'),
    '订单页必须在 onShow 通过 loadOrders 真实刷新'
  );
}
{
  const { normalizeAuxOrder } = require(path.join(root, 'utils/orders.js'));
  assert.equal(typeof normalizeAuxOrder, 'function', '必须导出储值/礼品卡订单适配函数');
  const stored = normalizeAuxOrder({ id: 1, orderNo: 'S1', amount: 20000, payStatus: 'PAID', createTime: '2026-09-17 10:00:00' }, 'stored-value');
  assert.equal(stored.category, 'stored-value', '储值订单必须标记 category');
  assert.equal(stored.totalAmount, 20000, '储值订单 amount 必须映射为统一金额字段（分）');
  const gift = normalizeAuxOrder({ id: 2, orderNo: 'G1', amount: 10000, payStatus: 'UNPAID', status: 'CREATED' }, 'gift-card');
  assert.equal(gift.category, 'gift-card', '礼品卡订单必须标记 category');
  assert.equal(gift.orderStatus, 'pending_payment', '未支付礼品卡订单必须归一为待支付');
}

// 后端字段映射：totalAmount（分）-> amountText（元）
{
  orderStore.setOrdersForTest([makeOrder({ id: 'order-money', totalAmount: 2000, discountAmount: 500 })]);
  const decorated = orderStore.getOrderById('order-money');
  assert.equal(decorated.amountText, '20', '订单金额必须由 totalAmount（分）换算为元展示');
  assert.equal(decorated.discountAmountText, '5', '订单优惠金额必须由 discountAmount（分）换算为元');
  assert.equal(decorated.couponAmountText, '0', '后端未下发 couponDiscount 时优惠券金额必须兜底为 0，不得空白');
  assert.equal(decorated.items[0].unitPrice, 100, '订单条目单价必须由分换算为元');
  assert.equal(decorated.items[0].originalPrice, 100, '订单条目原价必须由分换算为元');
}
// 缺少金额字段时不得抛异常（历史数据兼容）
{
  orderStore.setOrdersForTest([makeOrder({ id: 'order-noamount', totalAmount: undefined, amount: undefined })]);
  const decorated = orderStore.getOrderById('order-noamount');
  assert.equal(decorated.amountText, '0', '订单缺少金额字段时必须兜底为 0，不得抛异常');
}

// 用夹具替换本地镜像（模拟接口返回）
orderStore.setOrdersForTest(FIXTURES);

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

orderStore.setOrdersForTest(FIXTURES); // 订单页需要夹具
const ordersPageRoot = path.join(root, 'pages/orders/orders');
const ordersDefinition = loadPage(ordersPageRoot);
const ordersPage = createPageInstance(ordersDefinition);
ordersDefinition.onLoad.call(ordersPage);
assert.equal(ordersPage.data.activeTimeGroup, 'today', '订单页默认必须展示今日订单');
assert.ok(
  ordersPage.data.filteredOrders.every(order => order.timeGroup === 'today'),
  '订单页默认必须只过滤今日订单'
);
ordersDefinition.selectTimeGroup.call(ordersPage, { currentTarget: { dataset: { group: 'today' } } });
assert.equal(ordersPage.data.activeTimeGroup, 'today', '今日/历史页签必须可切换');
ordersDefinition.selectTimeGroup.call(ordersPage, { currentTarget: { dataset: { group: 'history' } } });
ordersDefinition.selectCategory.call(ordersPage, { currentTarget: { dataset: { id: 'gift-card' } } });
assert.ok(
  ordersPage.data.filteredOrders.every(order => order.timeGroup === 'history'),
  '历史页签必须只展示历史订单'
);
assert.ok(
  ordersPage.data.filteredOrders.every(order => order.category === 'gift-card'),
  '礼品卡分类必须只展示礼品卡订单'
);
// 取消订单必须真正调用后端接口（/orders/{orderNo}/cancel），
// 并等待「取消 -> 重新拉取列表」异步链完成后再断言状态。
remoteOrders.splice(0, remoteOrders.length, ...FIXTURES);
orderStore.setOrdersForTest(FIXTURES);
const giftPendingId = (orderStore.getOrders().find(order => order.isPendingPayment && order.category === 'gift-card') || orderStore.getOrders().find(order => order.isPendingPayment) || ordersPage.data.orders.find(order => order.isPendingPayment) || ordersPage.data.filteredOrders.find(order => order.isPendingPayment)).id;
const cancelTargetOrderNo = (orderStore.getOrderById(giftPendingId) || {}).orderNo || ((orderStore.getOrderById(giftPendingId) || {}).orderInfo || {}).orderNo || '';
ordersDefinition.cancelOrder.call(ordersPage, { currentTarget: { dataset: { id: giftPendingId } } });
await new Promise(resolve => setTimeout(resolve, 50));
assert.ok(
  calls.some(call => call.type === 'request' && call.method === 'POST' && call.url.endsWith('/cancel')),
  '取消订单必须调用后端取消接口，不得只改本地状态'
);
assert.ok(remoteCanceled.has(cancelTargetOrderNo), '取消接口必须携带正确的订单号');
assert.ok(ordersPage.data.orders.find(order => order.id === giftPendingId).isCanceled, '列表取消订单必须更新状态');

ordersDefinition.handlePay.call(ordersPage, { currentTarget: { dataset: { id: giftPendingId } } });
assert.ok(
  calls.some(call => call.type === 'toast' && call.title === '支付功能暂未接入'),
  '立即支付必须明确提示暂未接入'
);

orderStore.setOrdersForTest(FIXTURES); // 详情页需要夹具
const detailPageRoot = path.join(root, 'pages/order-detail/order-detail');
const detailDefinition = loadPage(detailPageRoot);
const detailPage = createPageInstance(detailDefinition);
detailDefinition.onLoad.call(detailPage, { id: 'order-010' });
assert.ok(
  detailPage.data.order.isPendingPayment && detailPage.data.order.countdownText,
  '订单详情必须展示待支付倒计时'
);
remoteOrders.splice(0, remoteOrders.length, ...FIXTURES);
orderStore.setOrdersForTest(FIXTURES);
detailDefinition.cancelOrder.call(detailPage, { currentTarget: { dataset: { id: 'order-010' } } });
await new Promise(resolve => setTimeout(resolve, 50));
assert.equal(detailPage.data.order.statusText, '已取消', '详情页取消后状态必须同步');

// 礼品卡订单页：真实接口模式（阶段 C 后不再走本地 mock 状态机）
const giftPageRoot = path.join(root, 'pages/gift-card-orders/gift-card-orders');
const giftDefinition = loadPage(giftPageRoot);
const giftPage = createPageInstance(giftDefinition);
giftDefinition.onLoad.call(giftPage, {});
assert.ok(
  giftPage.data.statusTabs.some(tab => tab.id === 'pending_verify' && tab.label === '待核销'),
  '礼品卡订单页必须包含待核销筛选'
);
assert.ok(
  giftPage.data.statusTabs.some(tab => tab.id === 'completed' && tab.label === '已完成'),
  '礼品卡订单页必须包含已完成筛选'
);
assert.ok(
  giftPage.data.statusTabs.some(tab => tab.id === 'canceled' && tab.label === '已取消'),
  '礼品卡订单页必须包含已取消筛选'
);
const giftWxml = fs.readFileSync(`${giftPageRoot}.wxml`, 'utf8');
const giftJs = fs.readFileSync(`${giftPageRoot}.js`, 'utf8');
assert.ok(
  giftJs.includes('fetchGiftCardOrders') && giftJs.includes('cancelGiftCardOrder'),
  '礼品卡订单页必须通过真实接口拉取订单并支持取消'
);
assert.ok(
  giftWxml.includes('wx:if="{{item.isPendingPayment}}"') &&
    giftWxml.includes('class="gift-order-card__countdown"') &&
    giftWxml.indexOf('gift-order-card__countdown') < giftWxml.indexOf('class="gift-order-card__status"'),
  '待支付剩余时间必须显示在状态上方'
);
assert.ok(
  giftWxml.includes('wx:if="{{item.payTimeText}}"') && !giftWxml.includes('{{item.orderInfo.createdAt}}'),
  '礼品卡订单列表必须只展示支付时间'
);
assert.ok(
  giftWxml.includes('catchtap="cancelOrder"') &&
    giftWxml.includes('catchtap="handlePay"') &&
    giftWxml.includes('取消订单') &&
    giftWxml.includes('继续支付'),
  '待支付礼品卡订单必须提供取消和继续支付操作'
);
assert.ok(
  giftWxml.includes('gift-order-card__main') &&
    giftWxml.includes('gift-order-card__info') &&
    giftWxml.includes('订单号 {{item.orderNo}}') &&
    giftWxml.includes('支付时间 {{item.payTimeText}}'),
  '礼品卡订单卡片必须保留订单号与支付时间信息'
);
const actionsStart = giftWxml.indexOf('class="gift-order-card__actions"');
const cardClose = giftWxml.indexOf('</view>', giftWxml.indexOf('aria-label="继续支付"'));
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
    ordersWxml.includes('wx:if="{{item.payTimeText}}"') &&
    ordersWxml.includes('支付时间 {{item.payTimeText}}'),
  '订单卡片必须显示订单号且已支付订单显示支付时间'
);
// 夹具与后端返回结构一致：校验「已支付有支付时间、未支付无支付时间」
assert.ok(
  FIXTURES.some(order => order.orderStatus !== 'pending_payment') &&
    FIXTURES.some(order => order.orderStatus === 'pending_payment'),
  '订单夹具必须覆盖已支付与未支付订单'
);
assert.ok(
  FIXTURES.filter(order => order.orderStatus !== 'pending_payment' && order.orderInfo.payMethod !== '未支付')
    .every(order => order.payTime),
  '已支付订单数据必须提供支付时间'
);
assert.ok(
  FIXTURES.filter(order => order.orderStatus === 'pending_payment' || order.orderInfo.payMethod === '未支付')
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

// 历史礼品卡元数据：卡面下架 / 面额软删后，记录自带字段必须优先于上架面额接口。
{
  const { pickGiftCardRecords, resolveGiftCardDisplay } = require(path.join(root, 'utils/gift-card.js'));
  assert.deepEqual(
    pickGiftCardRecords({ records: [{ id: 'order-1' }], total: 1 }),
    [{ id: 'order-1' }],
    '礼品卡订单必须继续兼容 PageResult.records'
  );
  assert.equal(
    resolveGiftCardDisplay({}, { cardName: '兜底卡名', cardImage: '/fallback.jpg' }).name,
    '兜底卡名',
    '卡自带元数据缺失时必须允许面额接口兜底'
  );

  const historicalCard = {
    id: 'card-history',
    denominationId: 'denom-removed',
    status: 'ACTIVE',
    cardName: '下架限定卡',
    cardImage: '/assets/images/3x/gift-card-limited.jpg',
    groupTitle: '历史卡面',
    amount: 50000,
    salePrice: 45000
  };
  const staleDenomination = {
    id: 'denom-removed',
    cardName: '错误的上架兜底名',
    cardImage: '/assets/images/3x/gift-card-jasmine.jpg'
  };
  giftHistoryState.cards = [historicalCard];
  giftHistoryState.denominations = [staleDenomination];
  giftHistoryState.orders = [];
  const cardDenominationCalls = giftHistoryState.denominationCalls;

  const myCardsRoot = path.join(root, 'pages/gift-card/gift-card');
  const myCardsDefinition = loadPage(myCardsRoot);
  const myCardsPage = createPageInstance(myCardsDefinition);
  myCardsDefinition.loadMyCards.call(myCardsPage);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(myCardsPage.data.myCards[0].name, historicalCard.cardName, '我的卡必须优先使用卡自带 cardName');
  assert.equal(myCardsPage.data.myCards[0].image, historicalCard.cardImage, '我的卡必须优先使用卡自带 cardImage');
  assert.equal(giftHistoryState.denominationCalls, cardDenominationCalls, '卡自带完整元数据时不得回落到面额接口');

  giftHistoryState.cards = [];
  giftHistoryState.orders = [{
    id: 'gift-order-history',
    orderNo: 'G-HISTORY',
    denominationId: staleDenomination.id,
    amount: historicalCard.amount,
    payStatus: 'PAID',
    status: 'CREATED',
    cardName: historicalCard.cardName,
    cardImage: historicalCard.cardImage,
    groupTitle: historicalCard.groupTitle,
    salePrice: historicalCard.salePrice
  }];
  const orderDenominationCalls = giftHistoryState.denominationCalls;
  const giftOrdersRoot = path.join(root, 'pages/gift-card-orders/gift-card-orders');
  const giftOrdersDefinition = loadPage(giftOrdersRoot);
  const giftOrdersPage = createPageInstance(giftOrdersDefinition);
  giftOrdersDefinition.refresh.call(giftOrdersPage);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(giftOrdersPage.data.allOrders[0].title, historicalCard.cardName, '订单列表必须优先使用 records.cardName');
  assert.equal(giftOrdersPage.data.allOrders[0].coverImage, historicalCard.cardImage, '订单列表必须优先使用 records.cardImage');
  assert.equal(giftHistoryState.denominationCalls, orderDenominationCalls, '订单自带完整元数据时不得回落到面额接口');

  giftHistoryState.cards = [historicalCard];
  storage['milkTea:auth:token'] = 'history-token';
  storage['milkTea:auth:user'] = { phone: '13612345792' };
  const profileRoot = path.join(root, 'pages/profile/profile');
  const profileDefinition = loadPage(profileRoot);
  const profilePage = createPageInstance(profileDefinition);
  const profileDenominationCalls = giftHistoryState.denominationCalls;
  profileDefinition.onShow.call(profilePage);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(profilePage.data.giftCards[0].name, historicalCard.cardName, '我的页历史礼品卡必须优先使用卡自带 cardName');
  assert.equal(profilePage.data.giftCards[0].image, historicalCard.cardImage, '我的页历史礼品卡必须优先使用卡自带 cardImage');
  assert.equal(giftHistoryState.denominationCalls, profileDenominationCalls, '我的页卡自带完整元数据时不得回落到面额接口');
  delete storage['milkTea:auth:token'];
  delete storage['milkTea:auth:user'];
}
console.log('订单分类、待支付倒计时与三类卡片测试通过');
