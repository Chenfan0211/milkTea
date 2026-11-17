const { request, unwrap } = require('./request');
const referrer = require('./referrer');

const STORAGE_KEY = 'milkTea:mall:pending-leader';
const LEADER_PARAM = 'leaderUserId';
const SHARE_USER_PARAM = 'shareUserId';
const ATTRIBUTION_URL = '/api/v1/app/leader/attribution';
const BIND_URL = '/api/v1/app/leader/bind-share';
const MALL_SHARE_ROUTES = new Set(['packageMall/pages/home/home', 'packageMall/pages/product-detail/product-detail']);
const activeBindings = new Map();
const memory = new Map();

function validId(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) return '';
  if (typeof value !== 'string' && typeof value !== 'number') return '';
  const id = String(value);
  return /^[1-9]\d{0,18}$/.test(id) && (id.length < 19 || id <= '9223372036854775807') ? id : '';
}

function currentUserId() {
  const auth = require('./auth');
  return auth.isLoggedIn() ? validId(auth.getUserId()) : '';
}

function pendingKey(userId) {
  // 已登录时按接收者隔离；游客的待处理链接在首次建立登录态时迁移。
  return userId ? `${STORAGE_KEY}:${userId}` : STORAGE_KEY;
}

function bindingPayload(options) {
  const leaderUserId = validId(options && options[LEADER_PARAM]);
  if (leaderUserId) return { leaderUserId };
  const shareUserId = validId(options && options[SHARE_USER_PARAM]);
  return shareUserId ? { shareUserId } : null;
}

function readPending(key) {
  if (memory.has(key)) return memory.get(key);
  try {
    const value = wx.getStorageSync(key);
    return bindingPayload(typeof value === 'string' ? { leaderUserId: value } : value);
  } catch (error) {
    return null;
  }
}

function writePending(key, binding) {
  try {
    if (binding) wx.setStorageSync(key, binding);
    else wx.removeStorageSync(key);
    memory.delete(key);
  } catch (error) {
    memory.set(key, binding);
  }
}

function isMallShareRoute(path) {
  return MALL_SHARE_ROUTES.has(String(path || '').replace(/^\//, ''));
}

function capture(options) {
  referrer.captureFromOptions(options);
  const binding = bindingPayload(options);
  if (!binding) return;
  writePending(pendingKey(currentUserId()), binding);
  retryPending();
}

function captureEntry(options) {
  if (options && isMallShareRoute(options.path)) capture(options.query);
}

function postBinding(binding) {
  return request({
    url: BIND_URL,
    method: 'POST',
    data: binding,
    silentAuth: true,
    retryAuth: false
  });
}

async function bind(binding, userId) {
  let result = await postBinding(binding);
  if (result && (result.code === 8888 || result.code === 9999)) {
    await require('./auth').ensureLogin(true);
    if (currentUserId() !== userId) throw new Error('登录账号已变化');
    result = await postBinding(binding);
  }
  // 当前接口只用 400 表示链接无效。其它异常保留本地待处理数据。
  if (result && result.code === 400) return;
  const attribution = unwrap(result);
  if (attribution && validId(attribution.leaderUserId)) return;
  if (binding.shareUserId && attribution && attribution.leaderUserId === null) return;
  throw new Error('团长归属响应异常');
}

async function confirmPending() {
  const userId = currentUserId();
  if (!userId) {
    if (readPending(STORAGE_KEY)) throw new Error('请登录后确认团长归属');
    return;
  }
  const key = pendingKey(userId);
  const guestBinding = readPending(STORAGE_KEY);
  if (guestBinding) {
    if (!readPending(key)) writePending(key, guestBinding);
    writePending(STORAGE_KEY, null);
  }
  let binding = readPending(key);
  while (binding && currentUserId() === userId) {
    let task = activeBindings.get(key);
    if (!task) {
      const target = binding;
      task = bind(target, userId)
        .then(() => {
          const pending = readPending(key);
          if (pending && pending.leaderUserId === target.leaderUserId && pending.shareUserId === target.shareUserId) {
            writePending(key, null);
          }
        })
        .finally(() => activeBindings.delete(key));
      activeBindings.set(key, task);
    }
    try {
      await task;
    } catch (error) {
      throw new Error('暂时无法确认团长归属，请重试');
    }
    binding = readPending(key);
  }
  if (currentUserId() !== userId) throw new Error('登录账号已变化，请重试');
}

function retryPending() {
  return confirmPending().catch(() => {});
}

async function fetchShareAttribution(userId) {
  await retryPending();
  if (!userId || currentUserId() !== userId) return null;
  const attribution = unwrap(await request({ url: ATTRIBUTION_URL, silentAuth: true }));
  if (!attribution || (attribution.leaderUserId !== null && !validId(attribution.leaderUserId))) return null;
  return currentUserId() === userId ? { userId, leaderUserId: validId(attribution && attribution.leaderUserId) } : null;
}

module.exports = {
  LEADER_PARAM,
  SHARE_USER_PARAM,
  capture,
  captureEntry,
  confirmPending,
  currentUserId,
  fetchShareAttribution,
  isMallShareRoute,
  retryPending
};
