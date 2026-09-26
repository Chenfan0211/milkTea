const { formatOrderAmount } = require('../data/mock');
const api = require('./api');
const { formatDateTime } = require('./date-format');

let orderStore = [];

/**
 * 待支付订单的支付时限（分钟）。
 *
 * 必须与后端一致：trade-service `OrderService.createOrder` 会发送
 * 「15 分钟未支付则自动关闭」的延迟消息，前端倒计时按同一时限推算，
 * 避免前后端对「是否超时」判断不一致。
 */
const PAYMENT_WINDOW_MINUTES = 15;
const PAYMENT_WINDOW_SECONDS = PAYMENT_WINDOW_MINUTES * 60;

/** 后端订单状态（英文枚举）-> 前端中文文案。 */
const STATUS_TEXT_MAP = {
  CREATED: '待支付',
  UNPAID: '未支付',
  PAID: '待核销',
  COMPLETED: '已完成',
  CANCELED: '已取消'
};

/** 不同订单来源的主状态文案互不复用，储值充值不得出现待核销等门店文案。 */
const ORDER_STATUS_TEXT = {
  store: {
    pending_payment: '待支付',
    pending_verify: '待核销',
    completed: '已完成',
    canceled: '已取消'
  },
  'gift-card': {
    pending_payment: '待支付',
    pending_verify: '待核销',
    completed: '已完成',
    canceled: '已取消'
  },
  'stored-value': {
    unpaid: '未支付',
    paid: '已支付'
  }
};

/**
 * 订单状态 -> 详情页顶部状态卡的「标题 + 说明」。
 *
 * 为什么单独维护：详情页 wxml 用的是 statusTitle / statusNote，
 * 而 statusText 是列表卡片的短文案（如「待支付」），二者语义不同 ——
 * 顶部状态卡需要更完整的表达（如「等待支付」+「请在 15 分钟内完成支付」）。
 */
const STATUS_META = {
  pending_payment: { title: '等待支付', note: '请在 15 分钟内完成支付，超时订单将自动关闭' },
  pending_verify: { title: '待核销', note: '请到店出示核销码完成取餐' },
  completed: { title: '已完成', note: '感谢惠顾，期待再次光临' },
  canceled: { title: '已取消', note: '订单已取消' },
  unpaid: { title: '未支付', note: '请完成充值支付，支付成功后余额将自动到账' },
  paid: { title: '已支付', note: '充值已到账，可在余额记录中查看' }
};

/** 支付方式（后端英文枚举 / 支付状态）-> 中文文案。 */
const PAY_METHOD_TEXT = {
  WXPAY: '微信支付',
  WECHAT: '微信支付',
  MOCK: '模拟支付',
  BALANCE: '储值余额',
  STORED_VALUE: '储值余额',
  UNPAID: '未支付',
  PAID: '微信支付',
  REFUNDED: '已退款',
  '' : ''
};

/**
 * 支付方式归一化为中文。
 *
 * 注意：后端 OrderDTO 没有独立的 payMethod 字段，只有 payStatus
 * （UNPAID / PAID）。因此这里优先读 payMethod，缺失时用 payStatus 兜底，
 * 避免详情页显示「UNPAID」这类英文枚举。
 */
function resolvePayMethodText(order) {
  if (!order) return '';
  const info = order.orderInfo || {};
  const raw = info.payMethod || order.payMethod || order.payStatus || '';
  const key = String(raw).toUpperCase();
  if (PAY_METHOD_TEXT[key] != null) return PAY_METHOD_TEXT[key];
  // 已是中文则原样返回
  return /[\u4e00-\u9fa5]/.test(String(raw)) ? String(raw) : String(raw);
}

/** 用餐方式（后端英文枚举 / 中文）-> 展示文案。 */
const MEAL_TYPE_TEXT = {
  dinein: '堂食',
  DINEIN: '堂食',
  DINE_IN: '堂食',
  pickup: '自取',
  PICKUP: '自取',
  takeout: '自取',
  TAKEOUT: '自取'
};

/**
 * 用餐方式归一化为中文。
 *
 * 下单时 mealType 传 'dinein'（店内就餐）/ 'pickup'（打包外带），
 * 后端 orders.meal_type 原样存储；列表卡片左上角标签与详情页「用餐信息」
 * 都依赖它，缺失会导致标签位置空白。
 */
function resolveMealTypeText(order) {
  if (!order) return '';
  const raw = order.mealType || (order.orderInfo && order.orderInfo.mealType) || '';
  if (!raw) return '';
  const text = MEAL_TYPE_TEXT[String(raw)] || MEAL_TYPE_TEXT[String(raw).toUpperCase()];
  if (text) return text;
  return /[\u4e00-\u9fa5]/.test(String(raw)) ? String(raw) : '';
}

/**
 * 详情页「用餐信息」分组（label/value 列表）。
 *
 * 由订单上的 mealType / storeName 组装，缺失用餐方式时不展示该行，
 * 避免出现空白 label。
 */
function buildMealInfo(order) {
  if (!order) return [];
  const mealText = resolveMealTypeText(order);
  const info = [];
  if (mealText) info.push({ label: '用餐方式', value: mealText });
  if (order.storeName) info.push({ label: '取餐门店', value: order.storeName });
  return info;
}

/** 各订单来源允许保留的前端内部状态码；外部枚举仍统一经 resolveOrderStatus 解析。 */
const INTERNAL_ORDER_STATUS = {
  store: ['pending_payment', 'pending_verify', 'completed', 'canceled'],
  'gift-card': ['pending_payment', 'pending_verify', 'completed', 'canceled'],
  'stored-value': ['unpaid', 'paid']
};

function resolveInternalOrderStatus(category, value) {
  const categoryKey = category === 'stored-value' || category === 'gift-card'
    ? category
    : 'store';
  const normalized = String(value || '');
  return INTERNAL_ORDER_STATUS[categoryKey].includes(normalized) ? normalized : '';
}

function normalizeOrderStatusValue(value) {
  return String(value || '').trim().toUpperCase();
}

function isPaidStatus(value) {
  const normalized = normalizeOrderStatusValue(value);
  return normalized === 'PAID' || normalized === '已支付';
}

function resolvePaidOrderStatus(payStatus) {
  const normalized = normalizeOrderStatusValue(payStatus);
  if (isPaidStatus(normalized)) return 'pending_verify';
  if (['UNPAID', 'CREATED', '待支付', '未支付'].includes(normalized)) return 'pending_payment';
  return '';
}

/**
 * 按订单来源解析主状态。
 *
 * store / gift-card 只使用四态；stored-value 只依据支付状态使用两态。
 * VERIFIED / REFUNDED 仅可作为业务辅助状态，不能覆盖订单主状态。
 */
function resolveOrderStatus(raw, category, payStatus) {
  const categoryKey = category === 'stored-value' || category === 'gift-card'
    ? category
    : 'store';
  const value = normalizeOrderStatusValue(raw);

  if (categoryKey === 'stored-value') {
    return isPaidStatus(payStatus || value) ? 'paid' : 'unpaid';
  }

  if (value === 'CANCELED' || value === '已取消') return 'canceled';
  if (value === 'COMPLETED' || value === '已完成') return 'completed';
  if (value === 'CREATED' || value === 'UNPAID' || value === '待支付' || value === '未支付') {
    return 'pending_payment';
  }
  if (value === 'PAID' || value === '待核销') return 'pending_verify';

  const paidOrderStatus = resolvePaidOrderStatus(payStatus);
  if (paidOrderStatus) return paidOrderStatus;


  return '';
}

function resolveOrderStatusText(orderStatus, category, rawStatus, payStatus) {
  const categoryKey = category === 'stored-value' || category === 'gift-card'
    ? category
    : 'store';
  const categoryText = ORDER_STATUS_TEXT[categoryKey] || ORDER_STATUS_TEXT.store;
  if (categoryText[orderStatus]) return categoryText[orderStatus];

  const resolvedStatus = resolveOrderStatus(rawStatus, categoryKey, payStatus);
  if (resolvedStatus && categoryText[resolvedStatus]) return categoryText[resolvedStatus];

  return STATUS_TEXT_MAP[normalizeOrderStatusValue(rawStatus)] || '';
}

/**
 * 后端 OrderDTO 使用扁平字段（createTime / orderNo / payStatus / store），
 * 而页面读取的是 orderInfo.* 与 storeName，因此这里做一次幂等归一化，
 * 让两套结构统一，避免订单号复制、创建时间、门店名全部取不到。
 */
function normalizeOrderShape(order) {
  if (!order || typeof order !== 'object') return order;
  const orderInfo = Object.assign({}, order.orderInfo || {});
  // 扁平 -> orderInfo（仅在 orderInfo 缺失对应字段时回填，保持幂等）
  if (!orderInfo.orderNo && order.orderNo) orderInfo.orderNo = order.orderNo;
  if (!orderInfo.createdAt && order.createTime) orderInfo.createdAt = order.createTime;
  if (!orderInfo.payMethod && order.payStatus) orderInfo.payMethod = order.payStatus;
  // 后端下发英文状态枚举，前端统一按 category 转为内部状态码与中文文案。
  // 注意：不能按「是否含下划线」判断内部码，completed / canceled / paid
  // 都不含下划线，必须直接按已知内部状态白名单保留。
  const category = order.category || 'store';
  const rawStatus = order.status || order.orderStatus;
  const payStatus = order.payStatus || orderInfo.payStatus || '';
  const orderStatus = resolveInternalOrderStatus(category, order.orderStatus)
    || resolveOrderStatus(rawStatus, category, payStatus)
    || resolveOrderStatus(order.orderStatus, category, payStatus)
    || '';
  const statusText = resolveOrderStatusText(orderStatus, category, rawStatus, payStatus);
  return Object.assign({}, order, {
    orderInfo,
    // 后端扁平 store -> 前端 storeName
    storeName: order.storeName || order.store || '',
    // 后端不下发 category（订单来源分类），缺失时按门店订单兜底，保证页签过滤与卡片样式正常
    category,
    orderStatus,
    status: statusText
  });
}

/**
 * 储值订单 / 礼品卡订单与门店订单结构不同：
 *   - 金额字段为 amount（分），门店订单为 totalAmount（分）
 *   - 无 items / store / pickupCode，页面上走封面卡片形态
 * 这里统一补齐前端订单卡片所需字段，使三类订单可共用同一套渲染与过滤逻辑。
 */
function normalizeAuxOrder(order, category) {
  if (!order || typeof order !== 'object') return order;
  const categoryKey = category === 'stored-value' || category === 'gift-card'
    ? category
    : 'store';
  const rawPayStatus = order.payStatus || (order.orderInfo && order.orderInfo.payStatus) || '';
  const payStatus = normalizeOrderStatusValue(rawPayStatus);
  const rawStatus = order.status || order.orderStatus;
  const orderStatus = resolveInternalOrderStatus(categoryKey, order.orderStatus)
    || resolveOrderStatus(rawStatus, categoryKey, payStatus);
  const statusText = resolveOrderStatusText(orderStatus, categoryKey, rawStatus, payStatus);
  return Object.assign({}, order, {
    category: categoryKey,
    // 统一金额字段名，交由 orderAmountYuan 按「分 -> 元」换算
    totalAmount: order.amount != null ? order.amount : order.totalAmount,
    createTime: order.createTime,
    orderStatus,
    status: statusText,
    // 储值充值对外只保留 UNPAID / PAID 两个支付状态。
    payStatus: categoryKey === 'stored-value'
      ? (orderStatus === 'paid' ? 'PAID' : 'UNPAID')
      : payStatus
  });
}

function cloneOrder(order) {
  const normalized = normalizeOrderShape(order) || {};
  return Object.assign({}, normalized, {
    // 条目补齐 id：后端 OrderDTO.Item 无 id，wx:key 需要稳定标识，避免渲染告警
    items: Array.isArray(normalized.items)
      ? normalized.items.map((item, index) =>
          Object.assign({}, item, {
            id: item.id || item.productId || 'item-' + index,
            image: item.image || '/assets/images/3x/menu-product.jpg'
          })
        )
      : [],
    orderInfo: Object.assign({}, normalized.orderInfo || {})
  });
}

/** 订单分页大小：与后端默认页大小保持一致 */
const ORDER_PAGE_SIZE = 20;

/** 把分页返回统一取成数组：兼容 PageResult 与裸数组两种结构 */
function pickRecords(res) {
  if (Array.isArray(res)) return res;
  if (res && Array.isArray(res.records)) return res.records;
  return [];
}

/**
 * 按当前页签请求对应订单来源（门店 / 储值 / 礼品卡）。
 *
 * 不再一次性请求全部订单接口；append=true 时追加当前筛选范围的数据。
 * @param {{page?: number, size?: number, append?: boolean, timeGroup?: string, category?: string}} options
 */
function refreshOrdersFromRemote(options = {}) {
  const page = Math.max(1, Number(options.page) || 1);
  const size = Math.max(1, Number(options.size) || ORDER_PAGE_SIZE);
  const append = Boolean(options.append);
  const timeGroup = options.timeGroup || 'today';
  const category = options.category || 'all';

  let requestPromise;
  if (category === 'stored-value') {
    requestPromise = api.fetchStoredValueOrders(page, size);
  } else if (category === 'gift-card') {
    requestPromise = api.fetchGiftCardOrders(page, size);
  } else {
    requestPromise = api.fetchOrders(page, size);
  }

  return requestPromise
    .then(records => {
      const mapped = pickRecords(records).map(order => {
        if (category === 'stored-value') return normalizeAuxOrder(order, 'stored-value');
        if (category === 'gift-card') return normalizeAuxOrder(order, 'gift-card');
        return Object.assign({}, order, { category: order.category || 'store' });
      });
      const batch = mapped.map(cloneOrder).map(order => Object.assign({}, order, { timeGroup }));
      const sameScope = order => order.timeGroup === timeGroup &&
        (category === 'all' || order.category === category);
      orderStore = append ? orderStore.filter(order => !sameScope(order)).concat(batch) : batch;
      const hasMore = Boolean(records && !Array.isArray(records) && Number(records.total || 0) > page * size);
      return { records: getOrders(), hasMore, page };
    })
    .catch(() => ({ records: getOrders(), hasMore: false, page }));
}

/**
 * 直接注入订单镜像（仅供测试使用）。
 * 生产代码请使用 refreshOrdersFromRemote()。
 */
function setOrdersForTest(list) {
  orderStore = (Array.isArray(list) ? list : []).map(cloneOrder);
  return getOrders();
}

/** 重置本地订单镜像（登录态切换或退出登录时调用）。 */
function resetOrders() {
  orderStore = [];
}

/** 已归一化的订单标记：避免 decorateOrder 被重复调用时金额被反复除以 100。 */
const NORMALIZED_FLAG = '__moneyNormalized';

/**
 * 后端订单金额以「分」下发（OrderDTO.totalAmount 等），前端展示统一为「元」。
 * 兼容两类数据源：
 *   - 后端真实结构：totalAmount / originalAmount / discountAmount / paidAmount（分）
 *   - 历史本地夹具：amount（分）
 */
function toYuan(fen) {
  const value = Number(fen);
  if (!Number.isFinite(value)) return 0;
  return Math.round(value) / 100;
}

/**
 * 取订单金额并换算为「元」，对同一订单保持幂等。
 *
 * 订单镜像里存的是「分」，而 decorateOrder 可能在每次 getOrders / getOrderById
 * 时被反复调用；若不记录换算状态，金额会被重复除以 100。
 */
function orderAmountYuan(order, field) {
  if (!order) return 0;
  const cacheKey = '__yuan_' + field;
  if (order[cacheKey] != null) return order[cacheKey];
  // 兼容历史字段名：amount 等价于 totalAmount
  let raw;
  if (field === 'totalAmount') {
    raw = order.totalAmount != null ? order.totalAmount : order.amount;
  } else {
    raw = order[field];
  }
  const yuan = toYuan(raw);
  // 回写缓存，保证同一订单对象再次 decorate 时结果稳定
  try { order[cacheKey] = yuan; } catch (error) { /* 只读对象忽略 */ }
  return yuan;
}

/** 订单条目金额（分 -> 元）。 */
function decorateItem(item) {
  if (!item || typeof item !== 'object') return item;
  if (item[NORMALIZED_FLAG]) return item;
  return Object.assign({}, item, {
    [NORMALIZED_FLAG]: true,
    unitPrice: Number.isFinite(Number(item.unitPrice)) ? toYuan(item.unitPrice) : item.unitPrice,
    originalPrice: Number.isFinite(Number(item.originalPrice)) ? toYuan(item.originalPrice) : item.originalPrice,
    subTotal: Number.isFinite(Number(item.subTotal)) ? toYuan(item.subTotal) : item.subTotal
  });
}

/**
 * 解析 iOS / Android 都能正确 parse 的时间字符串。
 *
 * `new Date('2026-09-25 10:00:00')` 在 iOS 上会得到 Invalid Date，
 * 因此统一把空格换成 T，并把纯日期补全为当天 00:00:00。
 */
function parseDateTime(value) {
  if (!value) return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
  const raw = String(value).trim();
  if (!raw) return null;
  // 「2026-09-25 10:00:00」->「2026-09-25T10:00:00」；已是 ISO 串则原样
  const normalized = raw.indexOf('T') !== -1 ? raw : raw.replace(' ', 'T');
  const date = new Date(normalized);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * 推算待支付订单的剩余支付秒数。
 *
 * 关键：后端 OrderDTO **不下发** remainingSeconds，因此必须由「创建时间 + 支付时限」
 * 推算。若直接依赖 remainingSeconds。缺失时会得到 0，导致倒计时定时器第一帧就把
 * 正常待支付订单误判为「支付超时 -> 已取消」。
 *
 * @param {Object} order 订单（读 orderInfo.createdAt / createTime / remainingSeconds）
 * @param {number} now 当前时间戳（便于测试注入）
 * @returns {number} 剩余秒数，>= 0；无法解析创建时间时返回 0
 */
function resolveRemainingSeconds(order, now) {
  if (!order) return 0;
  const current = Number.isFinite(Number(now)) ? Number(now) : Date.now();

  // 1) 优先用创建时间推算（后端权威数据）
  const createdRaw =
    (order.orderInfo && order.orderInfo.createdAt) || order.createTime || '';
  const created = parseDateTime(createdRaw);
  if (created) {
    const elapsed = Math.floor((current - created.getTime()) / 1000);
    return Math.max(0, PAYMENT_WINDOW_SECONDS - elapsed);
  }

  // 2) 兜底：显式下发的 remainingSeconds（历史夹具 / 未来后端补齐时可用）
  const fallback = Number(order.remainingSeconds);
  if (Number.isFinite(fallback) && fallback > 0) return Math.max(0, Math.floor(fallback));
  return 0;
}

function formatCountdown(seconds) {
  const safeSeconds = Math.max(0, Math.floor(Number(seconds) || 0));
  const minutes = Math.floor(safeSeconds / 60);
  const remainder = safeSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(remainder).padStart(2, '0')}`;
}

function decorateOrder(order, now) {
  const isCanceled = order.orderStatus === 'canceled' || order.status === '已取消';
  // 待支付：状态为 pending_payment 即可，倒计时仅用于展示。
  const isPendingPayment = order.orderStatus === 'pending_payment' && !isCanceled;
  // 倒计时按「创建时间 + 15 分钟支付时限」推算（后端不下发 remainingSeconds）；
  // 非待支付订单不再推算，避免历史订单拿到无意义的剩余时间。
  const remainingSeconds = isPendingPayment
    ? resolveRemainingSeconds(order, now)
    : Math.max(0, Number(order.remainingSeconds || 0));
  const cancelType = isCanceled
    ? order.cancelType || (order.orderInfo && order.orderInfo.payMethod === '未支付' ? 'pending' : 'paid')
    : '';
  const decoratedItems = Array.isArray(order.items) ? order.items.map(decorateItem) : [];
  const firstItem = decoratedItems[0] ? decoratedItems[0] : {};
  const previewItems = decoratedItems.slice(0, 2);

  // 状态卡的标题 / 说明：详情页顶部用它渲染，缺失会导致顶部状态区整块空白
  const statusKey = isCanceled
    ? 'canceled'
    : isPendingPayment
      ? 'pending_payment'
      : resolveInternalOrderStatus(order.category, order.orderStatus)
        || '';
  const statusMeta = STATUS_META[statusKey] || { title: '', note: '' };
  // 取消订单的说明按「待支付取消 / 已支付取消」区分，退款提示更准确
  const cancelNote =
    isCanceled && cancelType === 'paid' ? '订单已取消，退款将原路退回' : statusMeta.note;

  return Object.assign({}, order, {
    // 详情页顶部状态卡专用字段（列表用 statusText，语义不同）
    statusTitle: order.statusTitle || statusMeta.title || order.status || '',
    statusNote: order.statusNote || cancelNote || '',
    // 支付方式统一为中文，避免详情页出现 UNPAID 这类英文枚举
    payMethodText: resolvePayMethodText(order),
    items: decoratedItems,
    timeGroup: order.timeGroup || 'today',
    remainingSeconds,
    isPendingPayment,
    isCanceled,
    cancelType,
    statusText:
      isPendingPayment
        ? '待支付'
        : isCanceled
          ? '已取消'
          : order.orderStatus === 'unpaid'
            ? '未支付'
            : order.orderStatus === 'paid'
              ? '已支付'
              : order.status || (ORDER_STATUS_TEXT[order.category] || {})[order.orderStatus] || '',
    countdownText: formatCountdown(remainingSeconds),
    // 订单金额统一由「分」换算为「元」，并对已换算过的订单保持幂等
    amountText: formatOrderAmount(orderAmountYuan(order, 'totalAmount')),
    // 优惠金额：后端 discountAmount（分）-> 元；订单详情页「已优惠」用它
    discountAmountText: formatOrderAmount(orderAmountYuan(order, 'discountAmount')),
    // 优惠券抵扣：后端 OrderDTO 暂未下发 couponDiscount，缺失时按 0 展示，避免详情页出现空白
    couponAmountText: formatOrderAmount(orderAmountYuan(order, 'couponDiscount')),
    firstItem,
    previewItems,
    isPaidCancellable: order.category === 'store' && order.orderStatus === 'pending_verify' && !isCanceled,
    // 用餐方式：列表卡片左上角标签（堂食/自取），缺失会导致标签位置空白
    type: resolveMealTypeText(order),
    // 详情页「用餐信息」分组：用餐方式 + 取餐门店
    mealInfo: buildMealInfo(order),
    title: order.title || order.storeName || firstItem.name || '订单',
    coverImage: order.coverImage || firstItem.image || '',
    // 展示用字段：订单时间统一为中文完整格式，原始 orderInfo 保留供排序 / 逻辑使用。
    payTimeText: order.payTime ? formatDateTime(order.payTime) : '',
    createdAtText: order.orderInfo && order.orderInfo.createdAt ? formatDateTime(order.orderInfo.createdAt) : ''
  });
}

function sortOrders(list) {
  return list.slice().sort((left, right) => {
    if (left.isPendingPayment !== right.isPendingPayment) return left.isPendingPayment ? -1 : 1;
    if (left.isPendingPayment && right.isPendingPayment) return left.remainingSeconds - right.remainingSeconds;
    const leftTime = left.orderInfo && left.orderInfo.createdAt ? left.orderInfo.createdAt : '';
    const rightTime = right.orderInfo && right.orderInfo.createdAt ? right.orderInfo.createdAt : '';
    return rightTime.localeCompare(leftTime);
  });
}

/**
 * 按订单号从后端拉取单条订单并写回本地镜像。
 *
 * 背景（原 bug）：订单详情页原先只读本地 orderStore，而 orderStore 由列表页
 * 按「时间页签 + 分类」分批填充。用户从分享、消息、订单提醒等入口直接进入
 * 详情页时镜像是空的，页面会直接提示「订单不存在」并返回 —— 表现为详情页没数据。
 *
 * 这里改为优先请求后端单查（GET /api/v1/app/orders/{orderNo}），
 * 成功则写回镜像供后续复用；失败再回落本地镜像，保证离线场景仍可展示。
 *
 * @param {string} id 订单主键或订单号（页面传入的 id 两种都兼容）
 * @returns {Promise<Object|null>}
 */
function fetchOrderFromRemote(id) {
  const local = getOrderById(id);
  // 本地镜像已命中且有明细：直接返回，不再发请求。
  // 但镜像里若只有主表（无 items），仍要请求后端补齐明细，否则详情页是空的。
  if (local && Array.isArray(local.items) && local.items.length) return Promise.resolve(local);

  // 关键：详情接口按 order_no（订单号，字符串）查询，不是数据库主键 id。
  // 列表 data-id 传的是主键，这里必须优先取订单号，否则会把主键当订单号查 -> 404。
  const orderNo = resolveOrderNo(local, id);
  if (!orderNo) return Promise.resolve(local || null);

  return api
    .fetchOrderDetail(orderNo)
    .then(remote => {
      if (!remote || typeof remote !== 'object') return local || null;
      const merged = Object.assign({}, local || {}, remote, {
        id: (local && local.id) || remote.id || orderNo,
        orderNo: remote.orderNo || orderNo,
        category: (local && local.category) || remote.category || 'store',
        timeGroup: (local && local.timeGroup) || remote.timeGroup || 'today'
      });
      const normalized = cloneOrder(merged);
      const index = orderStore.findIndex(item => item.id === normalized.id);
      if (index === -1) orderStore = orderStore.concat([normalized]);
      else orderStore = orderStore.map(item => (item.id === normalized.id ? normalized : item));
      return getOrderById(normalized.id);
    })
    .catch(() => local || null);
}

/**
 * 从订单对象或入参里解析出「订单号」。
 *
 * 详情接口 GET /api/v1/app/orders/{orderNo} 走的是订单号精确匹配，
 * 而小程序列表的 data-id 传的是数据库主键，二者不是一回事。
 * 这里按 订单号 > 业务前缀串 的优先级解析，避免把主键当订单号查导致 404。
 */
function resolveOrderNo(order, fallback) {
  if (order) {
    const fromInfo = order.orderInfo && order.orderInfo.orderNo;
    if (fromInfo) return String(fromInfo);
    if (order.orderNo) return String(order.orderNo);
  }
  const raw = fallback == null ? '' : String(fallback);
  // 纯数字视为数据库主键，不当作订单号（订单号一定带业务前缀，如 WX / DEMO-O）
  if (!raw || /^\d+$/.test(raw)) return '';
  return raw;
}

function getOrders(now) {
  return sortOrders(orderStore.map(order => decorateOrder(order, now)));
}

/**
 * 按主键或订单号查订单。
 *
 * 兼容两种入口：列表点击传主键；分享 / 扫码 / 消息通知传的是订单号。
 * 二者都要能命中，否则从分享进入详情页会提示「订单不存在」。
 */
function getOrderById(id, now) {
  const key = id == null ? '' : String(id);
  if (!key) return null;
  const order =
    orderStore.find(item => String(item.id) === key) ||
    orderStore.find(item => {
      const orderNo = (item.orderInfo && item.orderInfo.orderNo) || item.orderNo;
      return orderNo && String(orderNo) === key;
    });
  return order ? decorateOrder(order, now) : null;
}

/**
 * 每秒刷新待支付订单的剩余时间。
 *
 * 重要（原 bug）：这里**只更新 remainingSeconds，绝不改订单状态**。
 *   - 后端才是「支付超时自动关闭」的权威方（RabbitMQ 15 分钟延迟消息 +
 *     定时补偿），前端擅自把订单标成「已取消」会与后端数据不一致；
 *   - 且后端不下发 remainingSeconds，旧实现拿到的初始值是 0，
 *     定时器第一帧就误判「支付超时」，把正常待支付订单全部打成「已取消」。
 * 因此现在：剩余时间由 resolveRemainingSeconds 按创建时间推算，
 * 归零后仅停留在 00:00 展示，下次拉取后端数据时会自然同步为真实状态。
 */
function tickOrderCountdowns(now) {
  const current = Number.isFinite(Number(now)) ? Number(now) : Date.now();
  orderStore = orderStore.map(order => {
    const normalized = Object.assign({}, order, { timeGroup: order.timeGroup || 'today' });
    if (normalized.orderStatus !== 'pending_payment') return normalized;
    return Object.assign({}, normalized, {
      remainingSeconds: resolveRemainingSeconds(normalized, current)
    });
  });
  return getOrders(current);
}

/**
 * 取消「未支付」订单（用户主动）。
 *
 * 必须调用后端接口：前端只做展示，状态以后端为准。
 * 原实现仅改本地镜像，导致「界面已取消、后端仍为待支付」的数据不一致，
 * 用户下拉刷新或换设备后又变回待支付。
 *
 * @param {string} id 订单主键
 * @returns {Promise<Object|null>} 取消后的订单（已从后端刷新）
 */
function cancelOrderById(id) {
  const order = getOrderById(id);
  if (!order || order.orderStatus !== 'pending_payment') {
    return Promise.resolve(getOrderById(id));
  }
  const orderNo = resolveOrderNo(order, id);
  if (!orderNo) return Promise.resolve(getOrderById(id));
  return api
    .cancelOrder(orderNo)
    .then(() => refreshOrdersFromRemote({ page: 1, size: ORDER_PAGE_SIZE }))
    .then(() => getOrderById(id));
}

/**
 * 取消「已支付待核销」订单（用户主动）。
 *
 * 后端走整单退款（原路退回 + 台账冲正），前端不再本地改状态。
 *
 * @param {string} id 订单主键
 * @returns {Promise<Object|null>} 取消后的订单（已从后端刷新）
 */
function cancelPaidOrderById(id) {
  const order = getOrderById(id);
  if (!order || order.orderStatus !== 'pending_verify' || order.category !== 'store') {
    return Promise.resolve(getOrderById(id));
  }
  const orderNo = resolveOrderNo(order, id);
  if (!orderNo) return Promise.resolve(getOrderById(id));
  return api
    .cancelOrder(orderNo)
    .then(() => refreshOrdersFromRemote({ page: 1, size: ORDER_PAGE_SIZE }))
    .then(() => getOrderById(id));
}

function addGiftCardOrder(order) {
  if (!order || !order.id) return null;
  orderStore = [cloneOrder(order)].concat(orderStore);
  return getOrderById(order.id);
}

// 门店核销兑换后，将匹配自提码的礼品卡订单置为已完成。
function markOrderVerified(pickupCode) {
  const code = String(pickupCode || '');
  if (!code) return null;
  let updated = null;
  orderStore = orderStore.map(order => {
    if (order.category !== 'gift-card' || order.pickupCode !== code) return order;
    updated = Object.assign({}, order, {
      status: '已完成',
      orderStatus: 'completed',
      statusTitle: '已完成',
      statusNote: '核销成功，感谢您的兑换'
    });
    return updated;
  });
  return updated;
}

function filterOrders(list, timeGroup, category) {
  return list.filter(order => order.timeGroup === timeGroup && (category === 'all' || order.category === category));
}

resetOrders();

module.exports = {
  ORDER_PAGE_SIZE,
  PAYMENT_WINDOW_MINUTES,
  PAYMENT_WINDOW_SECONDS,
  parseDateTime,
  resolveRemainingSeconds,
  pickRecords,
  normalizeAuxOrder,
  normalizeOrderShape,
  addGiftCardOrder,
  cancelOrderById,
  decorateOrder,
  filterOrders,
  formatCountdown,
  fetchOrderFromRemote,
  resolveOrderNo,
  getOrderById,
  getOrders,
  markOrderVerified,
  refreshOrdersFromRemote,
  setOrdersForTest,
  resetOrders,
  tickOrderCountdowns
};
