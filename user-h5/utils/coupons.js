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

/** 后端优惠券结构 -> 小程序券包展示结构。 */
function normalizeRemoteCoupon(item) {
  const amount = Math.round((Number(item.amount) || 0) / 100);
  const threshold = Math.round((Number(item.threshold) || 0) / 100);
  return {
    id: item.code || String(item.id),
    quantity: Number(item.count) || 1,
    type: item.type || 'voucher',
    displayType: 'fixed',
    amount,
    condition: threshold ? `满${threshold}元可用` : '不限',
    title: item.name || '',
    expiryText: '',
    brand: item.brand || '五零时光',
    couponNo: item.code || String(item.id),
    applicableStoreIds: Array.isArray(item.applicableStoreIds) ? item.applicableStoreIds : [],
    applicableProductIds: Array.isArray(item.applicableProductIds) ? item.applicableProductIds : [],
    applicableStores: '查看门店',
    applicableProducts: '查看适用商品',
    channel: '不限制',
    scenes: item.scenes || '',
    validityPeriod: '',
    usageTime: item.usageTime || '',
    paymentRestriction: '',
    description: item.description || '',
    source: item.source || '',
    expired: Boolean(item.expired),
    status: item.status || ''
  };
}

/** 从后端拉取券包并写入缓存。 */
function refreshCouponsFromRemote(status) {
  return api
    .fetchUserCoupons(status)
    .then(list => {
      if (Array.isArray(list)) {
        const coupons = list.map(normalizeRemoteCoupon);
        writeStorageCoupons(coupons);
        return coupons;
      }
      return getCoupons();
    })
    .catch(() => getCoupons());
}

/** 当前券包缓存（只读）。 */
function getCoupons() {
  return cloneCoupons(readStorageCoupons() || []);
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
  refreshCouponsFromRemote,
  normalizeRemoteCoupon
};
