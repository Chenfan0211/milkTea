/**
 * 诊断报告推荐购物车状态。
 *
 * 数据按用户和 reportId 隔离，持久化到本地缓存；每份购物车在下一次
 * 本地时间 01:00 统一失效。小程序关闭期间无法后台执行，由下次启动/显示
 * 和前台定时器补偿清理。
 */
const auth = require('./auth');
const { fenToYuan, yuanToFen } = require('./money');
const { mergeCartItem, removeCartItems } = require('./cart');

const STORAGE_KEY = 'milkTea:recommendation-carts';
const CLEANUP_HOUR = 1;
const CLEANUP_STATE_VERSION = 1;
const memoryState = { storage: null };

function cloneItems(items) {
  return (items || []).map(item => Object.assign({}, item));
}

function ownerId() {
  const value = auth.getUserId();
  return value === null || value === undefined ? '' : String(value);
}

function readStorage() {
  try {
    if (typeof wx !== 'undefined' && wx.getStorageSync) return wx.getStorageSync(STORAGE_KEY);
  } catch (error) {
    // 存储异常时使用模块内存兜底，避免阻断购物车会话。
  }
  return memoryState.storage;
}

function writeStorage(state) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) {
      wx.setStorageSync(STORAGE_KEY, state);
      return;
    }
  } catch (error) {
    // 存储异常时仍保留模块内存状态。
  }
  memoryState.storage = state;
}

function emptyState(owner) {
  return { version: CLEANUP_STATE_VERSION, ownerId: owner, carts: {} };
}

function normalizeState(raw, owner) {
  if (!raw || typeof raw !== 'object' || raw.ownerId !== owner || !raw.carts || typeof raw.carts !== 'object') {
    return emptyState(owner);
  }
  return { version: CLEANUP_STATE_VERSION, ownerId: owner, carts: raw.carts };
}

function readState() {
  const owner = ownerId();
  return normalizeState(readStorage(), owner);
}

function persist(state) {
  writeStorage({
    version: CLEANUP_STATE_VERSION,
    ownerId: state.ownerId,
    carts: state.carts || {}
  });
}

function normalizeReportId(reportId) {
  return String(reportId || '').trim();
}

/**
 * 下一次凌晨 1 点（设备本地时间）。
 * 刚好等于 1:00 时顺延到次日，避免刚创建就被判定为已过期。
 */
function nextCleanupAt(now) {
  const current = Number(now) || Date.now();
  const boundary = new Date(current);
  boundary.setHours(CLEANUP_HOUR, 0, 0, 0);
  if (boundary.getTime() <= current) boundary.setDate(boundary.getDate() + 1);
  return boundary.getTime();
}

function cleanupExpired(now) {
  const current = Number(now) || Date.now();
  const state = readState();
  let changed = false;
  Object.keys(state.carts).forEach(reportId => {
    const cart = state.carts[reportId];
    const expiresAt = cart && Number(cart.expiresAt);
    if (!cart || !Array.isArray(cart.items) || !Number.isFinite(expiresAt) || expiresAt <= current) {
      delete state.carts[reportId];
      changed = true;
    }
  });
  if (changed) persist(state);
  return changed;
}

function ensureCart(state, reportId) {
  const id = normalizeReportId(reportId);
  if (!id) return null;
  if (!state.carts[id] || !Array.isArray(state.carts[id].items)) {
    state.carts[id] = { items: [], settledIds: [], expiresAt: nextCleanupAt() };
  }
  if (!Array.isArray(state.carts[id].settledIds)) state.carts[id].settledIds = [];
  if (!Number.isFinite(Number(state.carts[id].expiresAt))) state.carts[id].expiresAt = nextCleanupAt();
  return state.carts[id];
}

function getItems(reportId) {
  const id = normalizeReportId(reportId);
  if (!id) return [];
  cleanupExpired();
  const cart = readState().carts[id];
  return cart && Array.isArray(cart.items) ? cloneItems(cart.items) : [];
}

function setItems(reportId, items) {
  const id = normalizeReportId(reportId);
  if (!id) return [];
  cleanupExpired();
  const state = readState();
  const nextItems = Array.isArray(items) ? cloneItems(items) : [];
  if (!nextItems.length) {
    delete state.carts[id];
    persist(state);
    return [];
  }
  const cart = ensureCart(state, id);
  cart.items = nextItems;
  persist(state);
  return cloneItems(cart.items);
}

function mergeItems(reportId, items) {
  const current = getItems(reportId);
  const merged = (items || []).reduce((list, item) => mergeCartItem(list, item), current);
  return setItems(reportId, merged);
}

function removeItems(reportId, ids) {
  return setItems(reportId, removeCartItems(getItems(reportId), ids));
}

function clear(reportId) {
  const id = normalizeReportId(reportId);
  if (!id) return [];
  cleanupExpired();
  const state = readState();
  delete state.carts[id];
  persist(state);
  return [];
}

function markSettled(reportId, ids) {
  const id = normalizeReportId(reportId);
  if (!id) return [];
  cleanupExpired();
  const state = readState();
  const cart = state.carts[id];
  if (!cart || !Array.isArray(cart.items) || !cart.items.length) return [];
  cart.settledIds = (Array.isArray(ids) ? ids : []).map(String).filter(Boolean);
  persist(state);
  return cloneItems(cart.items);
}

function consumeSettledItems(reportId) {
  const id = normalizeReportId(reportId);
  if (!id) return [];
  cleanupExpired();
  const state = readState();
  const cart = state.carts[id];
  if (!cart || !Array.isArray(cart.items)) return [];
  const settledIds = Array.isArray(cart.settledIds) ? cart.settledIds : [];
  if (!settledIds.length) return cloneItems(cart.items);
  cart.items = removeCartItems(cart.items, settledIds);
  cart.settledIds = [];
  if (!cart.items.length) delete state.carts[id];
  persist(state);
  return cloneItems(cart.items || []);
}

function summarizeItems(items) {
  const selectedItems = (items || []).filter(item => item.selected);
  return {
    count: selectedItems.reduce((sum, item) => sum + (Number(item.quantity) || 1), 0),
    total: fenToYuan(selectedItems.reduce((sum, item) => sum + yuanToFen(item.price) * (Number(item.quantity) || 1), 0))
  };
}

module.exports = {
  CLEANUP_HOUR,
  STORAGE_KEY,
  clear,
  cleanupExpired,
  consumeSettledItems,
  getItems,
  markSettled,
  mergeItems,
  nextCleanupAt,
  removeItems,
  setItems,
  summarizeItems
};
