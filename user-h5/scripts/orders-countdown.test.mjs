import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

/**
 * 待支付订单倒计时回归测试。
 *
 * 背景（真实故障）：后端 OrderDTO **不下发** remainingSeconds，
 * 而旧实现用 `remainingSeconds || 0` 当初始值，定时器第一帧就得到 0，
 * 于是把正常待支付订单误判为「支付超时」并改成「已取消」——
 * 表现为「未支付订单不显示倒计时，直接显示已取消」。
 *
 * 现在倒计时改为按「创建时间 + 15 分钟支付时限」推算，且定时器只更新秒数、
 * 绝不改订单状态（超时与否由后端权威判定）。
 */

globalThis.wx = {
  showToast() {},
  showModal() {},
  navigateTo() {},
  navigateBack() {},
  showShareMenu() {}
};
globalThis.getApp = () => ({ globalData: {} });

const orders = require(path.join(root, 'utils/orders.js'));

// 固定「现在」，避免依赖真实时间
const NOW = new Date('2026-09-25T10:00:00').getTime();

/** 造一条待支付订单，创建时间相对 NOW 偏移 secondsAgo 秒。 */
function pendingOrder(secondsAgo, id = 'order-p1') {
  const created = new Date(NOW - secondsAgo * 1000);
  const pad = n => String(n).padStart(2, '0');
  const createdAt =
    `${created.getFullYear()}-${pad(created.getMonth() + 1)}-${pad(created.getDate())} ` +
    `${pad(created.getHours())}:${pad(created.getMinutes())}:${pad(created.getSeconds())}`;
  return {
    id,
    category: 'store',
    timeGroup: 'today',
    orderStatus: 'pending_payment',
    status: '待支付',
    orderInfo: { orderNo: 'T-' + id, createdAt, payMethod: '未支付' }
  };
}

// 1. 支付时限常量必须与后端一致（15 分钟）
assert.equal(orders.PAYMENT_WINDOW_MINUTES, 15, '支付时限必须为 15 分钟（与后端延迟消息一致）');
assert.equal(orders.PAYMENT_WINDOW_SECONDS, 900, '支付时限秒数必须为 900');

// 2. 刚创建的订单：剩余时间应接近完整时限（而不是 0）
const fresh = orders.decorateOrder(pendingOrder(0), NOW);
assert.equal(fresh.isPendingPayment, true, '新创建的未支付订单必须判定为待支付');
assert.equal(fresh.remainingSeconds, 900, '刚创建的订单剩余时间必须是完整 900 秒');
assert.equal(fresh.countdownText, '15:00', '刚创建的订单倒计时必须显示 15:00');

// 3. 已过去 5 分钟：剩余 10 分钟
const after5m = orders.decorateOrder(pendingOrder(5 * 60), NOW);
assert.equal(after5m.remainingSeconds, 600, '过去 5 分钟后剩余必须为 600 秒');
assert.equal(after5m.countdownText, '10:00', '倒计时必须显示 10:00');

// 4. 关键回归：定时器 tick 不得把待支付订单改成已取消
orders.setOrdersForTest([pendingOrder(0)]);
orders.tickOrderCountdowns(NOW);
let list = orders.getOrders(NOW);
assert.equal(list.length, 1, 'tick 后订单不得消失');
assert.equal(list[0].orderStatus, 'pending_payment', 'tick 不得把待支付订单改成已取消');
assert.equal(list[0].statusText, '待支付', 'tick 后状态文案必须仍为待支付');
assert.equal(list[0].isCanceled, false, 'tick 不得把订单标记为已取消');
assert.ok(list[0].remainingSeconds > 0, 'tick 后剩余时间必须仍大于 0');

// 5. 已超时（创建超过 15 分钟）：剩余归 0，但状态仍由后端决定，前端不改
orders.setOrdersForTest([pendingOrder(20 * 60)]);
orders.tickOrderCountdowns(NOW);
list = orders.getOrders(NOW);
assert.equal(list[0].remainingSeconds, 0, '超时后剩余时间必须归 0');
assert.equal(
  list[0].orderStatus,
  'pending_payment',
  '前端不得擅自把超时订单改成已取消（超时与否由后端权威判定）'
);
assert.equal(list[0].statusText, '待支付', '超时订单在前端仍按后端状态展示');
assert.equal(list[0].countdownText, '00:00', '超时后倒计时显示 00:00');

// 6. 非待支付订单不参与倒计时推算
const paid = orders.decorateOrder(
  { id: 'o-paid', orderStatus: 'pending_verify', status: '待核销', orderInfo: { orderNo: 'X' } },
  NOW
);
assert.equal(paid.remainingSeconds, 0, '非待支付订单剩余时间必须为 0');
assert.equal(paid.isPendingPayment, false, '非待支付订单不得判定为待支付');

// 7. 时间解析必须兼容 iOS（空格格式不能被 iOS 直接 new Date）
assert.ok(orders.parseDateTime('2026-09-25 10:00:00'), '必须能解析「YYYY-MM-DD HH:mm:ss」格式');
assert.ok(orders.parseDateTime('2026-09-25T10:00:00'), '必须能解析 ISO 格式');
assert.equal(orders.parseDateTime(''), null, '空值必须返回 null');
assert.equal(orders.parseDateTime('not-a-date'), null, '非法时间必须返回 null');

// 8. 缺创建时间时安全兜底（不抛错）
const noTime = orders.decorateOrder(
  { id: 'o-notime', orderStatus: 'pending_payment', status: '待支付' },
  NOW
);
assert.equal(noTime.remainingSeconds, 0, '缺创建时间时剩余时间必须安全归 0');
assert.equal(noTime.countdownText, '00:00', '缺创建时间时倒计时显示 00:00');

// 9. 源码约束：tick 内不得出现改写状态的代码
const source = fs.readFileSync(path.join(root, 'utils/orders.js'), 'utf8');
const tickBody = source.match(/function tickOrderCountdowns[\s\S]*?\n}/)[0];
assert.ok(
  !/orderStatus:\s*'canceled'/.test(tickBody) && !/status:\s*'已取消'/.test(tickBody),
  'tickOrderCountdowns 内不得改写订单状态（超时应由后端判定）'
);

// 10. 详情页必须走后端单查（修复「从其他入口进入无数据」）
const detailJs = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.js'), 'utf8');
assert.ok(
  detailJs.includes('fetchOrderFromRemote'),
  '订单详情页必须优先请求后端单查，而非只读本地镜像'
);

console.log('待支付倒计时推算、超时不误杀与详情页取数测试通过');

// ===== 订单号解析：详情接口按 order_no 查询，不能用数据库主键 =====

// 11. 列表传入主键（纯数字）时，必须从订单对象里取出 orderNo 再请求
const withOrderNo = {
  id: '123',
  orderNo: 'WX202609250933406031',
  category: 'store',
  timeGroup: 'today',
  orderStatus: 'pending_payment',
  status: '待支付',
  items: [],
  orderInfo: { orderNo: 'WX202609250933406031', createdAt: '2026-09-25 09:33:40' }
};
assert.equal(
  orders.resolveOrderNo(withOrderNo, '123'),
  'WX202609250933406031',
  '必须优先使用 orderInfo.orderNo 作为订单号'
);
assert.equal(
  orders.resolveOrderNo({ orderNo: 'DEMO-O20260007' }, '999'),
  'DEMO-O20260007',
  'orderInfo 缺失时必须回落到顶层 orderNo'
);

// 12. 纯数字入参视为数据库主键，不能当订单号使用（否则必然 404）
assert.equal(
  orders.resolveOrderNo(null, '123'),
  '',
  '纯数字主键不得当作订单号请求详情接口'
);
assert.equal(
  orders.resolveOrderNo(null, 'WX202609250933406031'),
  'WX202609250933406031',
  '带业务前缀的订单号必须保留'
);
assert.equal(orders.resolveOrderNo(null, ''), '', '空入参必须返回空串');

// 13. getOrderById 必须同时支持主键与订单号两种入口
orders.setOrdersForTest([
  {
    id: '123',
    orderNo: 'WX202609250933406031',
    category: 'store',
    timeGroup: 'today',
    orderStatus: 'pending_payment',
    status: '待支付',
    items: [{ productId: 'p1', name: '抹茶', unitPrice: 1390, quantity: 1 }],
    orderInfo: { orderNo: 'WX202609250933406031', createdAt: '2026-09-25 09:33:40' }
  }
]);
assert.ok(orders.getOrderById('123'), '按数据库主键必须能查到订单');
assert.ok(
  orders.getOrderById('WX202609250933406031'),
  '按订单号必须能查到订单（分享 / 消息入口传的是订单号）'
);

// 14. 本地已有明细时不得再发请求（避免详情页多余的 404）
const detailSource = fs.readFileSync(path.join(root, 'utils/orders.js'), 'utf8');
assert.ok(
  /if \(local && Array\.isArray\(local\.items\) && local\.items\.length\) return Promise\.resolve\(local\)/.test(detailSource),
  '本地镜像已命中且有明细时必须直接返回，不再请求后端'
);

// 15. 缺明细时仍要请求后端补齐
assert.ok(
  detailSource.includes('resolveOrderNo(local, id)'),
  'fetchOrderFromRemote 必须通过 resolveOrderNo 解析订单号'
);

console.log('订单号解析（主键 vs orderNo）与详情页取数测试通过');

// ===== 订单详情页字段完整性：状态卡标题/说明 + 支付方式中文 =====

// 16. 待支付订单必须有 statusTitle / statusNote（缺失会导致详情页顶部状态区整块空白）
orders.setOrdersForTest([
  {
    id: 'o-pay',
    orderNo: 'WX202609250933406031',
    category: 'store',
    timeGroup: 'today',
    orderStatus: 'pending_payment',
    status: '待支付',
    payStatus: 'UNPAID',
    items: [],
    orderInfo: { orderNo: 'WX202609250933406031', createdAt: '2026-09-25 09:33:40' }
  }
]);
let detail = orders.getOrderById('o-pay', NOW);
assert.equal(detail.statusTitle, '等待支付', '待支付订单必须有状态标题（否则详情页顶部空白）');
assert.ok(detail.statusNote && detail.statusNote.length > 0, '待支付订单必须有状态说明');

// 17. 其余状态同样必须有标题 / 说明（逐一注入，避免相互污染）
const statusCases = [
  { orderStatus: 'pending_verify', status: '待核销', expect: '待核销' },
  { orderStatus: 'completed', status: '已完成', expect: '已完成' },
  { orderStatus: 'canceled', status: '已取消', expect: '已取消' }
];
for (const item of statusCases) {
  const id = 'o-' + item.orderStatus;
  orders.setOrdersForTest([
    {
      id,
      category: 'store',
      timeGroup: 'today',
      orderStatus: item.orderStatus,
      status: item.status,
      items: [],
      orderInfo: { orderNo: 'N-' + item.orderStatus, createdAt: '2026-09-25 09:00:00' }
    }
  ]);
  const one = orders.getOrderById(id, NOW);
  // 归一化后 orderStatus 必须保留（completed / canceled 不含下划线，曾被误清空）
  assert.equal(one.orderStatus, item.orderStatus, `${item.orderStatus} 归一化后不得被清空`);
  assert.equal(one.statusTitle, item.expect, `${item.orderStatus} 必须有状态标题`);
  assert.ok(one.statusNote, `${item.orderStatus} 必须有状态说明`);
}

// 18. 支付方式必须转为中文（后端只有 payStatus=UNPAID/PAID，不能直接展示英文枚举）
orders.setOrdersForTest([
  {
    id: 'o-method',
    category: 'store',
    timeGroup: 'today',
    orderStatus: 'pending_payment',
    status: '待支付',
    payStatus: 'UNPAID',
    items: [],
    orderInfo: { orderNo: 'M-1', createdAt: '2026-09-25 09:33:40' }
  }
]);
const unpaidOrder = orders.getOrderById('o-method', NOW);
assert.equal(unpaidOrder.payMethodText, '未支付', 'UNPAID 必须显示为「未支付」');

orders.setOrdersForTest([
  {
    id: 'o-method-paid',
    category: 'store',
    timeGroup: 'today',
    orderStatus: 'pending_verify',
    status: '待核销',
    payStatus: 'PAID',
    items: [],
    orderInfo: { orderNo: 'M-2', createdAt: '2026-09-25 09:33:40', payMethod: 'WXPAY' }
  }
]);
const paidOrder = orders.getOrderById('o-method-paid', NOW);
assert.ok(
  /[\u4e00-\u9fa5]/.test(paidOrder.payMethodText),
  '支付方式必须为中文，不得出现英文枚举'
);
assert.equal(paidOrder.payMethodText, '微信支付', 'WXPAY 必须显示为「微信支付」');

// 19. 详情页 wxml 必须绑定中文支付方式字段，不得直接用 orderInfo.payMethod
const detailWxml = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.wxml'), 'utf8');
assert.ok(
  detailWxml.includes('order.payMethodText'),
  '详情页支付方式必须绑定 payMethodText（中文），不得直接渲染 orderInfo.payMethod'
);
assert.ok(
  !/\{\{order\.orderInfo\.payMethod\}\}/.test(detailWxml),
  '详情页不得直接渲染 orderInfo.payMethod（会显示 UNPAID 等英文枚举）'
);
assert.ok(
  detailWxml.includes('order.statusTitle') && detailWxml.includes('order.statusNote'),
  '详情页顶部状态卡必须绑定 statusTitle / statusNote'
);

console.log('详情页状态卡与支付方式中文展示测试通过');