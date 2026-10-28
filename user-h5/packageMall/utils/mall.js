const api = require('../../utils/api');
const { resolveAssetUrl } = require('../../utils/asset-url');
const { parseDate, formatDateTime } = require('../../utils/date-format');
const { requestOrderPayment } = require('../../utils/order-payment');

const FALLBACK_IMAGE = '/assets/images/3x/menu-product.jpg';
const CATEGORY_PLACEHOLDER_IMAGE = '/assets/images/3x/mall-category-placeholder.jpg';
const AUTO_COMPLETE_DAYS = 15;

const STATUS_META = {
  CREATED: { text: '待支付', note: '请在 15 分钟内完成支付' },
  PAID: { text: '待发货', note: '商家正在准备商品' },
  SHIPPED: { text: '待收货', note: '商品已寄出，请留意物流' },
  COMPLETED: { text: '已完成', note: '订单已完成，感谢惠顾' },
  CANCELED: { text: '已取消', note: '订单已取消' },
  REFUNDED: { text: '已退款', note: '退款已完成' }
};

const PAY_METHOD_TEXT = {
  WXPAY: '微信支付',
  WECHAT: '微信支付',
  STORED_VALUE: '余额支付',
  BALANCE: '余额支付'
};

function normalizeStatus(value) {
  return String(value || '')
    .trim()
    .toUpperCase();
}

function moneyText(fen) {
  return String(api.fenToYuan(fen));
}

// ----------------------------------------------------------------
// 商城价格口径：公开商品页只展示 SKU 基础售价；
// 登录后的购物车与结算以服务端 /mall/checkout/quote 返回的最终成交价为准。
// ----------------------------------------------------------------
function effectivePrice(salePrice) {
  return Number(salePrice) || 0;
}

function priceText(fen) {
  return `¥${moneyText(fen)}`;
}

function imageUrl(value) {
  return resolveAssetUrl(value) || FALLBACK_IMAGE;
}

function plainText(value) {
  return String(value || '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/\s+/g, ' ')
    .trim();
}

function normalizeCategory(category) {
  return Object.assign({}, category, {
    id: Number(category && category.id),
    name: String((category && category.name) || '未分类'),
    iconImage: categoryIconUrl(category && category.iconImage)
  });
}

function categoryIconUrl(value) {
  return resolveAssetUrl(value) || CATEGORY_PLACEHOLDER_IMAGE;
}

function normalizeProduct(product) {
  const source = product || {};
  const availableStock = Math.max(0, Number(source.availableStock) || 0);
  const soldOut = Boolean(source.soldOut) || availableStock <= 0;
  const rawMinPrice = Number(source.minPrice) || 0;
  // 团长展示原售价；非团长展示「售价 + 分销佣金」
  const minPrice = effectivePrice(rawMinPrice);
  const originalPrice = Number(source.originalPrice) || 0;
  return Object.assign({}, source, {
    id: Number(source.id),
    categoryId: Number(source.categoryId),
    name: String(source.name || '商城商品'),
    subtitle: String(source.subtitle || ''),
    image: imageUrl(source.coverImage),
    minPrice,
    priceText: priceText(minPrice),
    originalPriceText: originalPrice > minPrice ? priceText(originalPrice) : '',
    availableStock,
    soldOut
  });
}

function normalizeSku(sku) {
  const source = sku || {};
  const availableStock = Math.max(0, Number(source.availableStock) || 0);
  const rawSalePrice = Number(source.salePrice) || 0;
  const salePrice = effectivePrice(rawSalePrice);
  const originalPrice = Number(source.originalPrice) || 0;
  return Object.assign({}, source, {
    id: Number(source.id),
    specValue: String(source.specValue || '默认规格'),
    image: imageUrl(source.image),
    salePrice,
    salePriceText: priceText(salePrice),
    originalPrice,
    originalPriceText: originalPrice > salePrice ? priceText(originalPrice) : '',
    availableStock,
    soldOut: Boolean(source.soldOut) || availableStock <= 0
  });
}

function normalizeDetail(detail) {
  const source = detail || {};
  const skus = (Array.isArray(source.skus) ? source.skus : []).map(normalizeSku);
  // 团长展示原售价；非团长展示「售价 + 分销佣金」。
  // SKU 使用服务端基础售价；登录后的购物车/结算再以服务端结算确认价覆盖。
  // 避免服务端 source.minPrice（原始售价）覆盖已叠加的展示价。
  const minPrice = skus.length ? skus[0].salePrice : effectivePrice(Number(source.minPrice) || 0);
  const originalPrice = Number(source.originalPrice) || (skus[0] ? skus[0].originalPrice : 0);
  return Object.assign({}, source, {
    id: Number(source.id),
    name: String(source.name || '商城商品'),
    subtitle: String(source.subtitle || ''),
    specName: String(source.specName || '规格'),
    categoryName: String(source.categoryName || ''),
    description: plainText(source.description),
    image: imageUrl(source.coverImage),
    galleryImages: (Array.isArray(source.galleryImages) ? source.galleryImages : []).map(imageUrl).filter(Boolean),
    minPrice,
    priceText: priceText(minPrice),
    originalPriceText: originalPrice > minPrice ? priceText(originalPrice) : '',
    skus
  });
}

function normalizeAddress(address) {
  const source = address || {};
  const regionText = [source.provinceName, source.cityName, source.districtName].filter(Boolean).join(' ');
  return Object.assign({}, source, {
    id: Number(source.id),
    regionText,
    fullAddress: `${regionText} ${String(source.detailAddress || '')}`.trim(),
    isDefault: Boolean(source.isDefault)
  });
}

function isListedReason(reason) {
  const text = String(reason || '');
  return text !== '商品已下架' && text !== 'SKU 不存在';
}

function normalizeValidatedItem(localItem, validated) {
  const local = localItem || {};
  const row = validated || {};
  const quantity = Math.max(1, Number(local.quantity) || 1);
  const reason = String(row.reason || '');
  const listed = Boolean(row.skuId) && isListedReason(reason);
  const availableStock = Math.max(0, Number(row.availableStock) || 0);
  const rawSalePrice = Number(row.salePrice) || Number(local.salePriceFen) || 0;
  const salePrice = effectivePrice(rawSalePrice);
  const originalPrice = Number(row.originalPrice) || Number(local.originalPriceFen) || 0;
  const valid = Boolean(row.valid) && listed;
  return Object.assign({}, local, {
    skuId: Number(row.skuId || local.skuId),
    productId: Number(row.productId || local.productId),
    name: String(row.productName || local.name || '商城商品'),
    skuCode: String(row.skuCode || local.skuCode || ''),
    specValue: String(row.specValue || local.specValue || '默认规格'),
    image: imageUrl(row.image || local.image),
    salePriceFen: salePrice,
    originalPriceFen: originalPrice,
    availableStock,
    listed,
    valid,
    reason: reason || (!valid ? '商品不可购买' : ''),
    quantity,
    selected: valid && quantity <= availableStock && local.selected !== false
  });
}

function validateItems(items) {
  const list = Array.isArray(items) ? items : [];
  if (!list.length) return Promise.resolve([]);
  return api
    .validateMallSkus(list.map(item => ({ skuId: Number(item.skuId), quantity: Number(item.quantity) || 1 })))
    .then(result => {
      const rows = (result && result.items) || [];
      const rowMap = rows.reduce((map, row) => {
        map[String(row.skuId)] = row;
        return map;
      }, {});
      return list.map(item => normalizeValidatedItem(item, rowMap[String(item.skuId)]));
    });
}

/** 商城结算确认：服务端返回当前用户最终成交价与应付合计。 */
function quoteItems(items) {
  const payload = (Array.isArray(items) ? items : []).map(item => ({
    skuId: Number(item.skuId),
    quantity: Math.max(1, Number(item.quantity) || 1)
  }));
  return api.quoteMallCheckout(payload);
}
function parseOrderDate(value) {
  return parseDate(value);
}

function countdownText(target, now) {
  const end = parseOrderDate(target);
  if (!end) return '';
  const remaining = end.getTime() - Number(now || Date.now());
  if (remaining <= 0) return '即将自动完成';
  const totalMinutes = Math.floor(remaining / 60000);
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  return days > 0 ? `${days}天${hours}小时${minutes}分` : `${hours}小时${minutes}分`;
}

function refundText(order) {
  const status = normalizeStatus(order && order.refundStatus);
  if (status === 'PENDING') return '退款处理中';
  if (status === 'FAILED') return '退款失败，可重试取消';
  return '';
}

function normalizeOrderItem(item, index) {
  const source = item || {};
  const unitPrice = Number(source.unitPrice) || 0;
  const quantity = Math.max(1, Number(source.quantity) || 1);
  return Object.assign({}, source, {
    key: String(source.id || `${source.productId || 'item'}-${index}`),
    name: String(source.name || source.productName || '商城商品'),
    spec: String(source.spec || source.specSnapshot || ''),
    image: imageUrl(source.image),
    quantity,
    unitPriceText: priceText(unitPrice),
    subTotalText: priceText(Number(source.subTotal) || unitPrice * quantity)
  });
}

function decorateOrder(order, now) {
  const source = order || {};
  const status = normalizeStatus(source.status);
  const meta = STATUS_META[status] || { text: status || '未知状态', note: '' };
  const payChannel = normalizeStatus(source.payChannel);
  const items = (Array.isArray(source.items) ? source.items : []).map(normalizeOrderItem);
  const payPending = status === 'CREATED' && normalizeStatus(source.payStatus) !== 'PAID';
  const refundPending = normalizeStatus(source.refundStatus) === 'PENDING';
  const activeAfterSale = Boolean(source.activeAfterSale);
  const canCancel = payPending || (status === 'PAID' && !refundPending);
  return Object.assign({}, source, {
    status,
    statusText: meta.text,
    statusNote: meta.note,
    statusClass: status.toLowerCase(),
    payChannel,
    payMethodText: PAY_METHOD_TEXT[payChannel] || (payPending ? '未支付' : '微信支付'),
    items,
    itemCount: items.reduce((sum, item) => sum + item.quantity, 0),
    amountText: moneyText(source.paidAmount || source.totalAmount),
    createTimeText: formatDateTime(source.createTime),
    payTimeText: formatDateTime(source.payTime),
    shippedTimeText: formatDateTime(source.shippedTime),
    completeTimeText: formatDateTime(source.completeTime),
    autoCompleteTimeText: formatDateTime(source.autoCompleteTime),
    autoCompleteCountdown: status === 'SHIPPED' ? countdownText(source.autoCompleteTime, now) : '',
    refundText: refundText(source),
    refundPending,
    payPending,
    canPay: payPending,
    canCancel,
    canConfirm: status === 'SHIPPED',
    canAfterSale: (status === 'SHIPPED' || status === 'COMPLETED') && !refundPending && !activeAfterSale,
    activeAfterSale,
    afterSaleStatus: normalizeStatus(source.afterSaleStatus),
    afterSaleNo: String(source.afterSaleNo || ''),
    shipping: Object.assign({}, source.shipping || {})
  });
}

function payExistingOrder(order) {
  const orderNo = String((order && order.orderNo) || '').trim();
  const amountFen = Number(order && (order.paidAmount || order.totalAmount)) || 0;
  if (!orderNo) return Promise.reject(new Error('订单号缺失，无法支付'));
  if (normalizeStatus(order.payChannel) === 'STORED_VALUE') {
    return api.payOrderByBalance(orderNo).then(result => ({
      paid: normalizeStatus(result && result.payStatus) === 'PAID',
      closed: normalizeStatus(result && result.status) === 'CANCELED',
      canceled: false,
      order: result
    }));
  }
  return requestOrderPayment(orderNo, amountFen, { maxAttempts: 10 });
}

module.exports = {
  AUTO_COMPLETE_DAYS,
  CATEGORY_PLACEHOLDER_IMAGE,
  FALLBACK_IMAGE,
  categoryIconUrl,
  decorateOrder,
  imageUrl,
  moneyText,
  normalizeAddress,
  normalizeCategory,
  normalizeDetail,
  normalizeProduct,
  normalizeSku,
  normalizeStatus,
  payExistingOrder,
  plainText,
  priceText,
  quoteItems,
  validateItems
};
