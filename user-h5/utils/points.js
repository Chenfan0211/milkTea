const api = require('./api');
const { getUserProfile, saveUserProfile } = require('./user-profile');

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

const { formatDateTime } = require('./date-format');

/**
 * 核销兑换自提码（真实接口）。
 *
 * 假数据清理：原实现把兑换自提码存在本地 Storage 的自建核销池，
 * 核销只是把本地条目标记 verified —— 既没落库、也无法跨设备，
 * 门店换个手机就核销不了，且同一码可被反复「核销」。
 * 现改为调用后端 POST /api/v1/app/gift-cards/verify（按订单号核销）。
 *
 * @param {string} pickupCode 自提码 / 订单号
 * @returns {Promise<{ok:boolean, reason?:string, order?:object}>}
 */
function verifyExchange(pickupCode) {
  const value = String(pickupCode || '').trim();
  if (!value) return Promise.resolve({ ok: false, reason: 'empty' });
  return api
    .verifyGiftCardOrder(value)
    .then(order => ({ ok: true, order }))
    .catch(error => {
      const message = String((error && error.message) || '');
      // 后端在重复核销 / 未支付时返回业务错误，这里归一化为可展示的原因
      if (message.indexOf('已核销') >= 0) return { ok: false, reason: 'already_verified', message };
      if (message.indexOf('不存在') >= 0) return { ok: false, reason: 'not_found', message };
      return { ok: false, reason: 'failed', message: message || '核销失败，请稍后重试' };
    });
}

module.exports = {
  formatDateTime,
  getPoints,
  setPoints,
  verifyExchange
};
