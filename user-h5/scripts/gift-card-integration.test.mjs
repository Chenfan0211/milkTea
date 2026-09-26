import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const config = require(path.join(root, 'config.js'));
const {
  resolveGiftCardDisplay,
  resolveGiftCardImageUrl,
  selectGiftCardDenominations
} = require(path.join(root, 'utils/gift-card.js'));

const normalizedBaseUrl = String(config.BASE_URL || '').replace(/\/+$/, '');
const publicImage = '/api/v1/files/public/11111111-1111-1111-1111-111111111111.jpg';
assert.equal(
  resolveGiftCardImageUrl(publicImage),
  normalizedBaseUrl + publicImage,
  '后台上传图片的相对地址必须转换为小程序可访问的绝对地址'
);
assert.equal(
  resolveGiftCardImageUrl('/assets/images/3x/gift-card-matcha.jpg'),
  '/assets/images/3x/gift-card-matcha.jpg',
  '小程序本地图片必须保持包内相对路径'
);
assert.equal(
  resolveGiftCardImageUrl('https://cdn.example.com/card.jpg'),
  'https://cdn.example.com/card.jpg',
  '已经是绝对地址的图片不得重复拼接域名'
);
assert.equal(
  resolveGiftCardDisplay({ cardName: '抹茶卡', cardImage: publicImage }).image,
  normalizedBaseUrl + publicImage,
  '历史礼品卡展示必须复用图片绝对地址解析'
);

const list = [
  { id: 11, groupId: 'popular', cardName: '抹茶卡', code: 'gift-a-100', amount: 10000 },
  { id: 12, groupId: 'popular', cardName: '抹茶卡', code: 'gift-a-200', amount: 20000 },
  { id: 21, groupId: 'limited', cardName: '抹茶卡', code: 'gift-b-100', amount: 10000 },
  { id: 31, groupId: 'popular', cardName: '茉莉卡', code: 'gift-c-100', amount: 10000 }
];

assert.deepEqual(
  selectGiftCardDenominations(list, { groupId: 'popular', cardName: '抹茶卡' }).map(item => item.id),
  [11, 12],
  '购买页必须按 groupId + cardName 精确匹配当前卡面'
);
assert.deepEqual(
  selectGiftCardDenominations(list, { id: '抹茶卡' }).map(item => item.id),
  [11, 12, 21],
  '旧链接仅携带卡面名称时仍须按卡面名称匹配，不得回退为全部数据'
);
assert.deepEqual(
  selectGiftCardDenominations(list, { id: '11' }).map(item => item.id),
  [11],
  '旧链接携带真实面额主键时必须精确匹配单个面额'
);
assert.deepEqual(
  selectGiftCardDenominations(list, { id: '不存在的卡面' }),
  [],
  '无效卡面参数不得回退展示全部礼品卡'
);

const giftPageJs = fs.readFileSync(path.join(root, 'pages/gift-card/gift-card.js'), 'utf8');
const giftPageWxml = fs.readFileSync(path.join(root, 'pages/gift-card/gift-card.wxml'), 'utf8');
const giftOrdersJs = fs.readFileSync(
  path.join(root, 'pages/gift-card-orders/gift-card-orders.js'),
  'utf8'
);
const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
const purchaseJs = fs.readFileSync(
  path.join(root, 'pages/gift-card-purchase/gift-card-purchase.js'),
  'utf8'
);
const purchaseWxml = fs.readFileSync(
  path.join(root, 'pages/gift-card-purchase/gift-card-purchase.wxml'),
  'utf8'
);
const giftOrdersWxml = fs.readFileSync(
  path.join(root, 'pages/gift-card-orders/gift-card-orders.wxml'),
  'utf8'
);
const apiJs = fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8');
const paymentHelperJs = fs.readFileSync(path.join(root, 'utils/gift-card-payment.js'), 'utf8');
assert.ok(giftPageJs.includes('groupId=${encodeURIComponent(groupId)}'), '卡面点击必须携带分组参数');
assert.ok(giftPageJs.includes('cardName=${encodeURIComponent(cardName)}'), '卡面点击必须携带卡面名称参数');
assert.match(giftPageWxml, /data-group-id/, '卡面列表必须把分组写入点击数据集');
assert.match(giftPageWxml, /data-card-name/, '卡面列表必须把卡面名称写入点击数据集');
assert.match(purchaseJs, /selectGiftCardDenominations/, '购买页必须使用统一卡面筛选函数');
assert.doesNotMatch(purchaseJs, /indexOf\(cardId\)/, '购买页不得使用面额编码前缀匹配卡面');
assert.match(purchaseJs, /denominationId:\s*item\.denominationId/, '支付数据必须使用真实面额主键');
assert.match(giftPageJs, /resolveGiftCardDisplay\(card\)/, '我的礼品卡必须直接使用卡记录元数据');
assert.doesNotMatch(giftPageJs, /needsGiftCardDenominations/, '我的礼品卡不得回退调用上架面额接口');
assert.doesNotMatch(giftOrdersJs, /fetchGiftCardDenominations/, '礼品卡订单不得依赖上架面额接口补全历史卡面');
assert.doesNotMatch(profileJs, /fetchGiftCardDenominations/, '个人资料页不得依赖上架面额接口补全历史卡面');
assert.doesNotMatch(purchaseJs, /id:\s*item\.code/, '面额选择控件不得把编码当作主键');
assert.match(
  apiJs,
  /function prepayGiftCard\(orderNo\)[\s\S]*?\/api\/v1\/app\/payments\/gift-card\/prepay[\s\S]*?data:\s*\{\s*orderNo\s*\}/,
  '小程序必须提供礼品卡微信预支付接口'
);
assert.match(
  apiJs,
  /function fetchGiftCardOrder\(orderNo\)[\s\S]*?\/api\/v1\/app\/gift-cards\/orders\/\$\{encodeURIComponent\(orderNo\)\}/,
  '小程序必须按订单号查询礼品卡订单'
);
assert.match(
  apiJs,
  /function refundGiftCard\(orderNo,\s*reason\)[\s\S]*?\/api\/v1\/app\/payments\/gift-card\/refund[\s\S]*?orderNo[\s\S]*?reason/,
  '小程序必须提供礼品卡微信原路退款接口'
);
assert.match(
  apiJs,
  /function cancelGiftCardOrder\(orderNo\)[\s\S]*?orders\/\$\{encodeURIComponent\(orderNo\)\}\/cancel/,
  '礼品卡取消订单必须统一使用 orderNo'
);
assert.match(apiJs, /prepayGiftCard,/, 'api.js 必须导出 prepayGiftCard');
assert.match(apiJs, /fetchGiftCardOrder,/, 'api.js 必须导出 fetchGiftCardOrder');
assert.match(apiJs, /refundGiftCard,/, 'api.js 必须导出 refundGiftCard');
assert.match(apiJs, /cancelGiftCardOrder,/, 'api.js 必须导出 cancelGiftCardOrder');

assert.match(purchaseJs, /selectedDenominationId/, '购买页必须维护唯一选中的面额');
assert.doesNotMatch(purchaseJs, /MAX_QUANTITY|changeQuantity|totalCount|item\.quantity/, '购买页不得保留数量加减或多件逻辑');
assert.match(purchaseJs, /purchaseGiftCard\(/, '购买页必须先创建礼品卡订单');
assert.match(purchaseJs, /requestGiftCardPayment\(/, '购买页必须复用真实支付与轮询流程');
assert.match(paymentHelperJs, /prepayGiftCard\(orderNo\)/, '支付流程必须先调用 prepayGiftCard');
assert.match(paymentHelperJs, /wx\.requestPayment\(/, '支付流程必须调起微信收银台');
assert.match(paymentHelperJs, /fetchGiftCardOrder\(orderNo\)/, '支付结果必须以服务端查单为依据');
assert.match(purchaseWxml, /1 张[^<]*应付售价/, '购买页必须固定展示 1 张和应付售价');
assert.doesNotMatch(purchaseWxml, /changeQuantity|gift-quantity/, '购买页 WXML 不得保留数量控件');

assert.match(giftOrdersJs, /requestGiftCardPayment\(orderNo\)/, '待支付订单必须复用真实支付流程');
assert.match(giftOrdersJs, /refundGiftCard\(orderNo/, '已支付未核销订单必须支持退款');
assert.match(giftOrdersJs, /cancelGiftCardOrder\(orderNo\)/, '未支付订单取消必须使用 orderNo');
assert.match(giftOrdersJs, /isRefunding/, '订单页必须识别退款中状态');
assert.match(giftOrdersJs, /isRefundFailed/, '订单页必须识别退款失败并支持重试');
assert.match(
  giftOrdersJs,
  /const isRefundFailed = !isRefunding\s+&& !isVerified\s+&& !isCanceled\s+&& refundStatus === 'FAILED';/,
  '退款失败重试必须排除退款中、已核销和已取消订单，并只读取退款辅助状态'
);
assert.match(giftOrdersWxml, /data-order-no="\{\{item\.orderNo\}\}"/, '订单操作必须携带 orderNo');
assert.match(giftOrdersWxml, /requestRefund/, '订单页必须提供退款入口');
assert.match(giftOrdersWxml, /重试退款/, '退款失败必须显示重试退款入口');
assert.match(
  giftOrdersWxml,
  /wx:if="\{\{item\.isPendingPayment\}\}">\s*<text wx:if="\{\{item\.countdownText\}\}" class="gift-order-card__countdown">/,
  '待支付倒计时为空时不得展示“剩余支付”'
);
assert.doesNotMatch(giftOrdersJs, /cancelGiftCardOrder\(id\)/, '取消订单不得继续使用 id');
assert.doesNotMatch(
  giftOrdersJs,
  /status === '(VERIFIED|REFUNDED|REFUNDING|REFUND_FAILED|CLOSED)'/,
  '礼品卡订单主状态只允许 CREATED / PAID / COMPLETED / CANCELED'
);


// 订单主状态口径：store / gift-card 四态，stored-value 两态。
const orders = require(path.join(root, 'utils/orders.js'));

function decorateRawOrder(order) {
  return orders.decorateOrder(
    orders.normalizeOrderShape(order),
    Date.parse('2026-09-26 10:05:00')
  );
}

function decorateAuxOrder(category, order) {
  const normalized = orders.normalizeAuxOrder(
    Object.assign({
      id: `${category}-1`,
      orderNo: `${category}-NO-1`,
      amount: 10000,
      createTime: '2026-09-26 10:00:00'
    }, order),
    category
  );
  return orders.decorateOrder(normalized, Date.parse('2026-09-26 10:05:00'));
}

const storeStateCases = [
  ['CREATED', 'pending_payment', '待支付'],
  ['PAID', 'pending_verify', '待核销'],
  ['COMPLETED', 'completed', '已完成'],
  ['CANCELED', 'canceled', '已取消']
];
storeStateCases.forEach(([raw, expectedStatus, expectedText]) => {
  const decorated = decorateRawOrder({
    id: `store-${raw}`,
    category: 'store',
    status: raw,
    totalAmount: 10000
  });
  assert.equal(decorated.orderStatus, expectedStatus, `普通订单 ${raw} 主状态错误`);
  assert.equal(decorated.statusText, expectedText, `普通订单 ${raw} 列表文案错误`);
});

const giftOrderStateCases = [
  ['CREATED', 'UNPAID', 'pending_payment', '待支付'],
  ['PAID', 'PAID', 'pending_verify', '待核销'],
  ['COMPLETED', 'PAID', 'completed', '已完成'],
  ['CANCELED', 'UNPAID', 'canceled', '已取消']
];
giftOrderStateCases.forEach(([raw, payStatus, expectedStatus, expectedText]) => {
  const decorated = decorateAuxOrder('gift-card', { status: raw, payStatus });
  assert.equal(decorated.orderStatus, expectedStatus, `礼品卡订单 ${raw} 主状态错误`);
  assert.equal(decorated.statusText, expectedText, `礼品卡订单 ${raw} 列表文案错误`);
});

const storedUnpaid = decorateAuxOrder('stored-value', { status: 'CREATED', payStatus: 'UNPAID' });
assert.equal(storedUnpaid.orderStatus, 'unpaid', '储值充值 UNPAID 必须归一为未支付内部状态');
assert.equal(storedUnpaid.statusText, '未支付', '储值充值 UNPAID 只能展示未支付');
assert.equal(storedUnpaid.statusTitle, '未支付', '储值充值未支付状态卡必须同步');
assert.ok(
  !['pending_verify', 'completed', 'canceled'].includes(storedUnpaid.orderStatus),
  '储值充值不得复用订单待核销/已完成/已取消主状态'
);

const storedPaid = decorateAuxOrder('stored-value', {
  status: 'COMPLETED',
  payStatus: 'PAID',
  verifyStatus: 'VERIFIED'
});
assert.equal(storedPaid.orderStatus, 'paid', '储值充值 PAID 必须归一为已支付内部状态');
assert.equal(storedPaid.statusText, '已支付', '储值充值 PAID 只能展示已支付');
assert.equal(storedPaid.statusTitle, '已支付', '储值充值已支付状态卡必须同步');
assert.ok(
  !['pending_verify', 'completed', 'canceled'].includes(storedPaid.orderStatus),
  '储值充值不得被 VERIFIED/COMPLETED 改写成待核销或已完成'
);

const storedPaidFromLegacyInternal = decorateAuxOrder('stored-value', {
  orderStatus: 'completed',
  payStatus: 'PAID'
});
assert.equal(
  storedPaidFromLegacyInternal.orderStatus,
  'paid',
  '储值充值即使收到旧内部状态 completed，也必须按 category 归一为 paid'
);
assert.equal(
  storedPaidFromLegacyInternal.statusTitle,
  '已支付',
  '储值充值旧内部状态不得让详情状态卡显示已完成'
);

const storedPaidFromShape = decorateRawOrder({
  id: 'stored-value-legacy-shape',
  category: 'stored-value',
  orderStatus: 'completed',
  payStatus: 'PAID',
  totalAmount: 10000
});
assert.equal(
  storedPaidFromShape.orderStatus,
  'paid',
  '通用订单归一化必须按 stored-value category 覆盖旧内部状态'
);
assert.equal(
  storedPaidFromShape.statusTitle,
  '已支付',
  '通用订单详情状态卡必须与储值两态归一结果一致'
);

const giftVerified = decorateAuxOrder('gift-card', {
  status: 'VERIFIED',
  payStatus: 'PAID',
  verifyStatus: 'VERIFIED'
});
assert.equal(giftVerified.orderStatus, 'pending_verify', '礼品卡 VERIFIED 不得再作为已完成主状态判断');
assert.equal(giftVerified.statusText, '待核销', '礼品卡 VERIFIED 主状态仍须展示待核销');

const giftRefunded = decorateAuxOrder('gift-card', {
  status: 'REFUNDED',
  payStatus: 'PAID',
  refundStatus: 'REFUNDING'
});
assert.equal(giftRefunded.orderStatus, 'pending_verify', '礼品卡退款状态不得覆盖主状态');
assert.equal(giftRefunded.statusText, '待核销', '礼品卡退款中主状态仍须展示待核销');

assert.doesNotMatch(giftOrdersJs, /id:\s*'refunding'/, '礼品卡订单页不得保留独立退款中主页签');
assert.doesNotMatch(
  giftOrdersJs,
  /statusId\s*===\s*'refunding'/,
  '礼品卡订单筛选不得再把退款中作为主状态筛选'
);
assert.match(
  giftOrdersJs,
  /id:\s*'completed',\s*label:\s*'已完成'/,
  '礼品卡订单已完成筛选必须使用四态文案'
);
