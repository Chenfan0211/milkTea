const api = require('./api');
const { formatOrderAmount } = require('../data/mock');

/**
 * 优惠券本地缓存。
 *
 * 阶段 C 后优惠券以后端接口为准：
 * - 页面先 await refreshCouponsFromRemote() 拉取并写入缓存；
 * - getCoupons() 只读缓存，未拉取到时返回空数组；
 * - addCoupon() 仅用于兑换后把新券追加到缓存（真实发券以后端为准）。
 */

const COUPON_STORAGE_KEY = 'milkTea:coupons';

/**
 * 「可用券张数」独立缓存。
 *
 * 为什么不能直接数券包列表：券包缓存是全 Tab 共用的一个键，
 * 用户点过「已使用」Tab 后缓存里就全是已使用券（usable=false），
 * 首页/我的页若按它现算会把张数算成 0。这里只在「未使用口径」的拉取时写入。
 */
const USABLE_COUNT_STORAGE_KEY = 'milkTea:coupons:usable-count';

function cloneCoupons(list) {
  return (list || []).map(item => Object.assign({}, item));
}

function readStorageCoupons() {
  try {
    const cached = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(COUPON_STORAGE_KEY) : null;
    if (Array.isArray(cached)) return cached;
  } catch (error) {
    // ignore storage errors
  }
  const app = typeof getApp === 'function' ? getApp() : null;
  return app && app.globalData && Array.isArray(app.globalData.coupons) ? app.globalData.coupons : null;
}

function writeStorageCoupons(list) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(COUPON_STORAGE_KEY, list);
  } catch (error) {
    // ignore storage errors
  }
  const app = typeof getApp === 'function' ? getApp() : null;
  if (app && app.globalData) app.globalData.coupons = list;
}

function readUsableCouponCount() {
  try {
    const cached = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(USABLE_COUNT_STORAGE_KEY) : null;
    if (typeof cached === 'number' && cached >= 0) return cached;
  } catch (error) {
    // ignore storage errors
  }
  return null;
}

function writeUsableCouponCount(count) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(USABLE_COUNT_STORAGE_KEY, count);
  } catch (error) {
    // ignore storage errors
  }
}

function formatDateTime(value) {
  if (!value) return '';
  const text = String(value).replace('T', ' ');
  return text.length >= 16 ? text.slice(0, 16) : text;
}

/**
 * 延迟生效提示文案。
 *
 * 分享有礼券等「领取后 N 天才生效」的券，生效前归入「待生效」Tab，
 * 需要在收益说明里明确告诉用户还要等几天，避免误以为券坏了。
 */
function buildEffectiveDelayText(item) {
  const delayDays = Number(item.effectiveDelayDays);
  if (!Number.isFinite(delayDays) || delayDays <= 0) return '';
  return `领取后 ${delayDays} 天生效`;
}

function buildRemoteValidityPeriod(item) {
  const start = formatDateTime(item.validityStart);
  const end = formatDateTime(item.validityEnd || item.expireAt);
  if (start && end) return `${start}~${end}`;
  if (end) return `有效期至 ${end}`;
  return '长期有效';
}

/** 后端用户券结构 -> 小程序券包展示结构。 */
function normalizeRemoteCoupon(item) {
  const source = item || {};
  const amountFen = Number(source.amount) || 0;
  const thresholdFen = Number(source.threshold) || 0;
  const amount = Math.round(amountFen / 100);
  const threshold = Math.round(thresholdFen / 100);
  const stableId = source.couponCode || source.code || source.couponId || '';
  return {
    id: item.id != null ? String(item.id) : String(stableId),
    couponId: source.couponId,
    quantity: 1,
    type: source.type || 'voucher',
    displayType: 'fixed',
    amount,
    amountFen,
    thresholdFen,
    condition: thresholdFen ? `满${thresholdFen / 100}元可用` : '不限',
    title: source.name || '',
    expiryText: source.expireAt ? `${formatDateTime(source.expireAt)} 到期` : '长期有效',
    brand: source.brand || '五零时光',
    couponNo: source.couponCode || source.code || String(stableId),
    applicableStoreIds: Array.isArray(source.applicableStoreIds) ? source.applicableStoreIds : [],
    applicableProductIds: Array.isArray(source.applicableProductIds) ? source.applicableProductIds : [],
    applicableStores: '查看门店',
    applicableProducts: '查看适用商品',
    channel: '不限制',
    scenes: source.scenes || '',
    validityStart: source.validityStart || '',
    validityEnd: source.validityEnd || '',
    expireAt: source.expireAt || '',
    validityPeriod: buildRemoteValidityPeriod(source),
    usageTime: source.usageTime || '',
    paymentRestriction: '',
    description: source.description || '',
    source: source.source || '',
    effectiveDelayDays: Number.isFinite(Number(source.effectiveDelayDays)) ? Number(source.effectiveDelayDays) : 0,
    effectiveDelayText: buildEffectiveDelayText(source),
    // 过期由服务端统一判定（UserCouponView.expired）：不能用 usable === false 推断 ——
    // 已使用 / 订单占用中的券同样是 usable === false，会被误当成「已过期」。
    expired: Boolean(source.expired) || source.status === 'EXPIRED',
    usable: source.usable !== false,
    locked: source.status === 'LOCKED',
    status: source.status || ''
  };
}
/** 从后端拉取券包并写入缓存；失败向上抛出，页面据此展示错误态。 */
function refreshCouponsFromRemote(status) {
  return api.fetchUserCoupons(status).then(list => {
    if (!Array.isArray(list)) {
      throw new Error('优惠券接口返回格式异常');
    }
    const coupons = list.map(normalizeRemoteCoupon);
    writeStorageCoupons(coupons);
    // 只有「未使用口径」的结果能代表可用券张数：其它 Tab 不得污染这个计数
    if (!status || status === 'UNUSED') {
      writeUsableCouponCount(coupons.filter(c => c.usable !== false && !c.expired).length);
    }
    return coupons;
  });
}
/** 当前券包缓存（只读）。 */
function getCoupons() {
  return cloneCoupons(readStorageCoupons() || []);
}

/**
 * 当前可用券数（首页「优惠券N张」、我的页展示用）。
 *
 * 口径 = 「未使用」Tab 里的可用券：已生效、未过期、未被订单占用；
 * 待生效（PENDING）与订单占用中（LOCKED）都不计入。
 */
function getUsableCouponCount() {
  const cached = readUsableCouponCount();
  if (cached !== null) return cached;
  // 兼容还没有独立计数缓存的老数据：按券包缓存现算
  return getCoupons().filter(c => c.usable !== false && !c.expired).length;
}

function formatDatePart(value) {
  return String(value).padStart(2, '0');
}

function buildExpiryText(now) {
  const end = new Date(now.getTime());
  end.setDate(end.getDate() + 7);
  const pad = formatDatePart;
  return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())} 23:59:00 到期`;
}

function buildValidityPeriod(now) {
  const end = new Date(now.getTime());
  end.setDate(end.getDate() + 7);
  const pad = formatDatePart;
  const startText = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} 00:00:00`;
  const endText = `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())} 23:59:59`;
  return `${startText}~${endText}`;
}

function buildCouponFromProduct(product, quantity, now, seq) {
  const dateTime = now.getTime();
  const name = product.name || '时光币兑换券';
  return {
    id: `coupon-exchange-${dateTime}-${seq}`,
    quantity: quantity || 1,
    type: 'voucher',
    displayType: product.displayType || 'fixed',
    amount: product.amount || 0,
    condition: product.condition || '不限',
    title: name,
    expiryText: buildExpiryText(now),
    brand: '五零时光',
    couponNo: `${dateTime}${seq}`,
    applicableStoreIds: [],
    applicableProductIds: [],
    applicableStores: '查看门店',
    applicableProducts: '查看适用商品',
    channel: '不限制',
    scenes: '买单、堂食(门店就餐)、堂食(打包外带)',
    validityPeriod: buildValidityPeriod(now),
    usageTime: '00:00:00~23:59:59',
    paymentRestriction: '',
    description: product.description || '兑换所得优惠券，请在有效期内使用。',
    source: '时光币兑换',
    expanded: false
  };
}

/** 兑换成功后把新券追加到券包头部（真实发券以后端为准）。 */
function addCoupon(product, quantity) {
  const now = new Date();
  const list = getCoupons();
  const seq = list.length + 1;
  const coupon = buildCouponFromProduct(product, quantity, now, seq);
  const next = [coupon].concat(list);
  writeStorageCoupons(next);
  return { coupon, coupons: next };
}

module.exports = {
  addCoupon,
  getCoupons,
  getUsableCouponCount,
  refreshCouponsFromRemote,
  normalizeRemoteCoupon,
  buildEffectiveDelayText
};
