const { userProfile: defaultProfile } = require('../data/mock');

const PROFILE_STORAGE_KEY = 'milkTea:user-profile';

function cloneProfile(profile) {
  const source = profile && typeof profile === 'object' ? profile : {};
  return Object.assign({}, defaultProfile, source, {
    balance: defaultProfile.balance,
    region: Array.isArray(source.region)
      ? source.region.filter(Boolean).slice(0, 3)
      : Array.isArray(defaultProfile.region)
        ? defaultProfile.region.slice(0, 3)
        : [],
    giftCards: Array.isArray(source.giftCards)
      ? source.giftCards.map(item => Object.assign({}, item))
      : defaultProfile.giftCards.map(item => Object.assign({}, item))
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

function getUserProfile() {
  return cloneProfile(readStorage());
}

function saveUserProfile(profile) {
  const normalized = cloneProfile(profile);
  writeStorage(normalized);
  return normalized;
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
  formatBirthday,
  getDaysInMonth,
  getDefaultBirthday,
  getUserProfile,
  maskPhone,
  saveUserProfile
};
