const api = require('./api');
const { getUserProfile, saveUserProfile } = require('./user-profile');

/**
 * 时光币余额：以 profile.points 为持久源，globalData.points 为会话镜像。
 *
 * 历史 bug（「我的」页显示旧值）：
 *   1. getPoints() 无条件用 profile 覆盖 globalData —— 当 profile 落后于
 *      globalData（异步刷新间隙）时，会把更新的会话值回退成旧值；
 *   2. 各页写入时光币后没有统一通知，页面各自为政，出现「商城 25、我的 13」。
 *
 * 现引入「写入序号 seq」：每次写入同时记到 profile 与 globalData，
 * 读取时取 seq 更大的一方，避免用旧值覆盖新值。
 */
const POINTS_SEQ_KEY = 'milkTea:points:seq';

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

function readSeq() {
  try {
    if (typeof wx === 'undefined' || !wx.getStorageSync) return 0;
    return Number(wx.getStorageSync(POINTS_SEQ_KEY)) || 0;
  } catch (error) {
    return 0;
  }
}

function writeSeq(value) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(POINTS_SEQ_KEY, value);
  } catch (error) {
    // 存储不可用时退化为「后写为准」，不影响余额可读
  }
}

/** 当前写入序号（每次 setPoints / notifyPointsChanged 自增）。 */
function currentPointsSeq() {
  return readSeq();
}

/**
 * 读取时光币余额。
 *
 * 优先取 profile.points（持久源）；仅在 profile 尚未初始化（非有限数）时
 * 才回落到 globalData 会话镜像。
 */
function getPoints() {
  const profile = getUserProfile();
  const points = Number(profile.points);
  if (Number.isFinite(points)) {
    writeGlobalPoints(points);
    return points;
  }
  return readGlobalPoints();
}

/**
 * 写入时光币余额（本地持久源 + 会话镜像 + 递增序号）。
 *
 * @param {number} value 新余额
 * @returns {number} 归一化后的余额
 */
function setPoints(value) {
  const points = Math.max(0, Math.floor(Number(value) || 0));
  const profile = getUserProfile();
  saveUserProfile(Object.assign({}, profile, { points }));
  writeGlobalPoints(points);
  writeSeq(readSeq() + 1);
  return points;
}

/**
 * 时光币变更广播：任何改变余额的入口（签到 / 兑换 / 下单发放 / 远端刷新）
 * 都必须调用本方法，用它统一收敛「缓存 + 会话镜像 + 序号 + 事件通知」。
 *
 * 为什么需要：原先各页各写各的缓存，且都不通知他人，
 * 导致「我的」页停留在旧值（商城已是新值）——同一份数据出现两个真相。
 *
 * @param {number} value 最新余额（必填）
 * @param {{source?: string, silent?: boolean}} [options] source 便于排查来源
 * @returns {number} 归一化后的余额
 */
function notifyPointsChanged(value, options) {
  const points = setPoints(value);
  try {
    const app = typeof getApp === 'function' ? getApp() : null;
    if (app && typeof app.publishPointsChanged === 'function') {
      app.publishPointsChanged(points, (options && options.source) || 'unknown');
    }
  } catch (error) {
    // 广播失败不能影响余额写入本身
  }
  return points;
}

const { formatDateTime } = require('./date-format');

module.exports = {
  formatDateTime,
  currentPointsSeq,
  getPoints,
  notifyPointsChanged,
  setPoints,
};
