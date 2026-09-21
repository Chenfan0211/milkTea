const { coupons: sourceCoupons } = require('../data/mock');

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
  return (app && app.globalData && Array.isArray(app.globalData.coupons)) ? app.globalData.coupons : null;
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

// 初始优惠券：mock 数据为基底，Storage/globalData 有值时优先。
function getCoupons() {
  const stored = readStorageCoupons();
  if (stored) return cloneCoupons(stored);
  const initial = cloneCoupons(sourceCoupons);
  writeStorageCoupons(initial);
  return initial;
}

function formatDatePart(value) {
  return String(value).padStart(2, '0');
}

function buildExpiryText(now) {
  const end = new Date(now.getTime());
  end.setDate(end.getDate() + 7);
  const pad = formatDatePart;
  return `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())} 23:59 到期`;
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

// 兑换优惠券：追加到优惠券列表头部，返回新列表。
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
  getCoupons
};
