const { getUserProfile, saveUserProfile } = require('./user-profile');
const { addGiftCardOrder, markOrderVerified } = require('./orders');
const { addCoupon } = require('./coupons');

// 自提码同日自增序号，避免同毫秒/并发下 code 重复
let pickupCodeSeq = 0;

function readGlobalPoints() {
  try {
    const app = typeof getApp === 'function' ? getApp() : null;
    return app && app.globalData ? app.globalData.points : 0;
  } catch (error) {
    return 0;
  }
}

function writeGlobalPoints(value) {
  try {
    const app = typeof getApp === 'function' ? getApp() : null;
    if (app && app.globalData) app.globalData.points = value;
  } catch (error) {
    // Keep the profile value usable when globalData is unavailable.
  }
}

function getAppOrNull() {
  try {
    return typeof getApp === 'function' ? getApp() : null;
  } catch (error) {
    return null;
  }
}

// 时光币以 profile.points 为持久源，globalData.points 为会话镜像。
function getPoints() {
  const profile = getUserProfile();
  const points = Number(profile.points);
  if (Number.isFinite(points)) {
    writeGlobalPoints(points);
    return points;
  }
  return readGlobalPoints();
}

function setPoints(value) {
  const points = Math.max(0, Math.floor(Number(value) || 0));
  const profile = getUserProfile();
  saveUserProfile(Object.assign({}, profile, { points }));
  writeGlobalPoints(points);
  return points;
}

function formatDateTime(now = new Date()) {
  const pad = n => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
}

function formatDateKey(now = new Date()) {
  const pad = n => String(n).padStart(2, '0');
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
}

// 生成自提码：CZ + yyyyMMdd + 6 位同日自增序号（去随机，避免并发重复）
function generatePickupCode(now = new Date()) {
  pickupCodeSeq += 1;
  const seq = String(pickupCodeSeq % 1000000).padStart(6, '0');
  return 'CZ' + formatDateKey(now) + seq;
}

const EXCHANGE_POOL_KEY = 'milkTea:exchange:pool';

// 兑换核销池：以 Storage 为持久源，globalData 为会话镜像。
function readExchangeVerifyPool() {
  try {
    const cached = typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(EXCHANGE_POOL_KEY) : null;
    if (Array.isArray(cached)) return cached;
  } catch (error) {
    // ignore storage errors
  }
  const app = getAppOrNull();
  return (app && app.globalData && app.globalData.exchangeVerifyPool) || [];
}

function writeExchangeVerifyPool(pool) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(EXCHANGE_POOL_KEY, pool);
  } catch (error) {
    // ignore storage errors
  }
  const app = getAppOrNull();
  if (app && app.globalData) app.globalData.exchangeVerifyPool = pool;
}

function exchangeProduct(options = {}) {
  const { product, quantity = 1, currentPoints, pointsRecords = [], exchangeRecords = [] } = options;
  const cost = Math.floor((product.points || 0) * quantity);
  const balance = Number.isFinite(Number(currentPoints)) ? Number(currentPoints) : getPoints();

  if (balance < cost) {
    return { ok: false, reason: 'insufficient', points: balance };
  }

  const isCoupon = product.category === 'coupon';

  const nextPoints = setPoints(balance - cost);
  const now = new Date();
  const dateTime = formatDateTime(now);
  const pickupCode = isCoupon ? '' : generatePickupCode(now);

  const pointsRecord = {
    id: `points-exchange-${now.getTime()}`,
    title: product.name || '时光币兑换',
    date: dateTime,
    amount: `-${cost}`,
    source: '时光币兑换'
  };
  const exchangeRecord = {
    id: `exchange-${now.getTime()}`,
    name: product.name || '时光币兑换',
    status: isCoupon ? 'completed' : 'pending_verify',
    pickupCode,
    amount: `${cost}`,
    product: product.name || '时光币兑换',
    spec: product.spec || '兑换商品',
    createdAt: dateTime
  };

  const nextPointsRecords = [pointsRecord].concat(pointsRecords || []);
  const nextExchangeRecords = [exchangeRecord].concat(exchangeRecords || []);

  if (isCoupon) {
    // 优惠券：直接到账，订单为已完成，不进入核销池、无自提码。
    addGiftCardOrder({
      id: exchangeRecord.id,
      category: 'gift-card',
      timeGroup: 'history',
      type: '礼品卡',
      title: product.name || '时光币兑换',
      storeName: '五零时光礼品卡',
      status: '已完成',
      orderStatus: 'completed',
      statusTitle: '已完成',
      statusNote: '兑换成功，优惠券已发放到账',
      payTime: dateTime,
      amount: cost,
      count: quantity,
      coverImage: product.image || '',
      isExchange: true,
      items: [
        {
          id: `${exchangeRecord.id}-item`,
          name: product.name || '时光币兑换',
          spec: product.spec || '兑换商品',
          image: product.image || '',
          unitPrice: cost,
          originalPrice: cost,
          quantity
        }
      ],
      orderInfo: {
        orderNo: `EX${now.getTime()}`,
        createdAt: dateTime,
        payMethod: '时光币兑换'
      }
    });

    // 优惠券数量增加
    const profile = getUserProfile();
    saveUserProfile(Object.assign({}, profile, { couponCount: (Number(profile.couponCount) || 0) + quantity }));

    // 追加优惠券到优惠券列表
    addCoupon(product, quantity);
  } else {
    // 实物/公益：写入兑换核销池 + 待核销礼品卡订单。
    const poolEntry = {
      id: exchangeRecord.id,
      pickupCode,
      orderNo: pickupCode,
      product: product.name || '时光币兑换',
      spec: product.spec || '兑换商品',
      image: product.image || '',
      amount: `${cost} 时光币`,
      verified: false
    };
    const existingPool = readExchangeVerifyPool();
    const nextPool = [poolEntry].concat(existingPool);
    writeExchangeVerifyPool(nextPool);

    addGiftCardOrder({
      id: exchangeRecord.id,
      category: 'gift-card',
      timeGroup: 'history',
      type: '礼品卡',
      title: product.name || '时光币兑换',
      storeName: '五零时光礼品卡',
      status: '待核销',
      orderStatus: 'pending_verify',
      statusTitle: '待核销',
      statusNote: '兑换成功，请到店出示二维码核销',
      payTime: dateTime,
      amount: cost,
      count: quantity,
      coverImage: product.image || '',
      pickupCode,
      isExchange: true,
      items: [
        {
          id: `${exchangeRecord.id}-item`,
          name: product.name || '时光币兑换',
          spec: product.spec || '兑换商品',
          image: product.image || '',
          unitPrice: cost,
          originalPrice: cost,
          quantity
        }
      ],
      orderInfo: {
        orderNo: pickupCode,
        createdAt: dateTime,
        payMethod: '时光币兑换'
      }
    });
  }

  const app = getAppOrNull();
  if (app && app.globalData) {
    app.globalData.pointsRecords = nextPointsRecords;
    app.globalData.exchangeRecords = nextExchangeRecords;
  }

  return {
    ok: true,
    points: nextPoints,
    cost,
    pickupCode,
    pointsRecord,
    exchangeRecord,
    pointsRecords: nextPointsRecords,
    exchangeRecords: nextExchangeRecords
  };
}

// 核销兑换自提码：匹配独立兑换池、去重，返回核销结果。
function verifyExchange(pickupCode) {
  const value = String(pickupCode || '').trim();
  if (!value) return { ok: false, reason: 'empty' };

  const pool = readExchangeVerifyPool();
  const entry = pool.find(item => item.pickupCode === value || item.orderNo === value);
  if (!entry) return { ok: false, reason: 'not_found' };
  if (entry.verified) return { ok: false, reason: 'already_verified' };

  entry.verified = true;
  writeExchangeVerifyPool(pool);

  // 同步礼品卡订单列表中的待核销订单为已完成
  markOrderVerified(entry.pickupCode || entry.orderNo);

  // 同步兑换记录状态
  const app = getAppOrNull();
  if (app && app.globalData) {
    const records = app.globalData.exchangeRecords || [];
    app.globalData.exchangeRecords = records.map(item =>
      item.pickupCode === value || item.id === entry.id ? Object.assign({}, item, { status: 'verified' }) : item
    );
  }

  return { ok: true, entry };
}

module.exports = {
  exchangeProduct,
  formatDateTime,
  generatePickupCode,
  getPoints,
  setPoints,
  verifyExchange
};
