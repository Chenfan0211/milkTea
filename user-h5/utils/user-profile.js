const api = require('./api');

/**
 * 用户资料本地缓存。
 *
 * 阶段 C 后资料以后端 /api/v1/app/auth/me 为准：
 * - 页面先 await refreshUserProfileFromRemote() 拉取并写入缓存；
 * - 本地读写函数（getUserProfile / saveUserProfile）只操作缓存，
 *   未拉取到时返回空壳对象，页面按「无数据」处理。
 */

const PROFILE_STORAGE_KEY = 'milkTea:user-profile';

const EMPTY_PROFILE = {
  nickname: '',
  phone: '',
  gender: '',
  birthday: '',
  region: [],
  avatar: '',
  vipLevel: '',
  nextLevel: '',
  totalSpend: 0,
  couponCount: 0,
  balance: 0,
  points: 0,
  growth: 0,
  giftCards: []
};

/** 后端 AppUser -> 小程序页面资料结构。 */
function normalizeRemoteProfile(user) {
  if (!user) return null;
  return {
    nickname: user.nickName || '',
    phone: user.phone || '',
    gender: user.gender || '',
    birthday: user.birthday || '',
    region: [],
    avatar: user.avatar || '',
    vipLevel: user.vipLevel || '',
    nextLevel: '',
    totalSpend: 0,
    couponCount: 0,
    // balance 后端为「分」，页面按「元」展示
    balance: Math.round((Number(user.balance) || 0) / 100),
    points: Number(user.points) || 0,
    growth: 0,
    giftCards: []
  };
}

function cloneProfile(profile) {
  const source = profile && typeof profile === 'object' ? profile : {};
  return Object.assign({}, EMPTY_PROFILE, source, {
    region: Array.isArray(source.region) ? source.region.filter(Boolean).slice(0, 3) : [],
    giftCards: Array.isArray(source.giftCards) ? source.giftCards.map(item => Object.assign({}, item)) : []
  });
}

function readStorage() {
  try {
    return typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(PROFILE_STORAGE_KEY) : '';
  } catch (error) {
    return '';
  }
}

function writeStorage(profile) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(PROFILE_STORAGE_KEY, profile);
  } catch (error) {
    // Keep the in-memory result usable when storage is unavailable.
  }
}

/** 从后端拉取用户资料并写入本地缓存。 */
function refreshUserProfileFromRemote() {
  return api
    .fetchUserProfile()
    .then(user => {
      const normalized = normalizeRemoteProfile(user);
      if (normalized) writeStorage(normalized);
      return normalized || getUserProfile();
    })
    .catch(() => getUserProfile());
}

function getUserProfile() {
  return cloneProfile(readStorage());
}

function saveUserProfile(profile) {
  const normalized = cloneProfile(profile);
  writeStorage(normalized);
  return normalized;
}

/** 退出登录时清空本地资料缓存。 */
function clearUserProfile() {
  writeStorage(EMPTY_PROFILE);
}

function maskPhone(phone) {
  const value = String(phone || '');
  if (value.length < 7) return value;
  return `${value.slice(0, 3)}****${value.slice(-4)}`;
}

function formatDatePart(value) {
  return String(value).padStart(2, '0');
}

function formatBirthday(year, month, day) {
  return `${year}-${formatDatePart(month)}-${formatDatePart(day)}`;
}

function getDefaultBirthday(now = new Date()) {
  const date = new Date(now.getTime());
  date.setFullYear(date.getFullYear() - 18);
  return formatBirthday(date.getFullYear(), date.getMonth() + 1, date.getDate());
}

function getDaysInMonth(year, month) {
  return new Date(Number(year), Number(month), 0).getDate();
}

module.exports = {
  PROFILE_STORAGE_KEY,
  EMPTY_PROFILE,
  formatBirthday,
  getDaysInMonth,
  getDefaultBirthday,
  getUserProfile,
  maskPhone,
  saveUserProfile,
  clearUserProfile,
  normalizeRemoteProfile,
  refreshUserProfileFromRemote
};
