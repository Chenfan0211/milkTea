/**
 * 舌诊资料适配层：本地缓存优先，账号资料仅作为首次兜底。
 *
 * 口径：
 *   · 用户每次点击测试都会先经过个人信息弹层；
 *   · 弹层保存只写本地缓存，不回写用户表；
 *   · 无缓存时才请求 /auth/me，账号字段逐项缺失时再回落默认值；
 *   · 默认性别为男，默认年龄为 20 岁（按当前年份折算出生年）。
 */
const api = require('./api');

const TONGUE_PROFILE_STORAGE_KEY = 'milkTea:tongue-profile';
const DEFAULT_GENDER = 'male';
const DEFAULT_AGE = 20;
const MIN_BIRTH_YEAR = 1900;
const GENDERS = ['male', 'female'];

function readStorage(key) {
  try {
    return typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(key) : '';
  } catch (error) {
    return '';
  }
}

function writeStorage(key, value) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(key, value);
  } catch (error) {
    // 本地存储不可用时仍允许当前流程继续。
  }
}

/** 性别归一化：只接受 male / female，其余按未填写处理。 */
function normalizeGender(value) {
  const gender = String(value || '')
    .trim()
    .toLowerCase();
  return GENDERS.indexOf(gender) >= 0 ? gender : '';
}

/** 出生年归一化：1900 ~ 当前年份之间才算有效。 */
function normalizeBirthYear(value, now = new Date()) {
  const text = String(value === null || value === undefined ? '' : value).trim();
  if (!/^\d{4}$/.test(text)) return '';
  const year = Number(text);
  const maxYear = now.getFullYear();
  if (!Number.isFinite(year) || year < MIN_BIRTH_YEAR || year > maxYear) return '';
  return String(year);
}

/** 生成 1900 到当前年份的升序选择项。 */
function birthYearOptions(now = new Date()) {
  const maxYear = Number((now || new Date()).getFullYear());
  const years = [];
  for (let year = MIN_BIRTH_YEAR; year <= maxYear; year += 1) years.push(String(year));
  return years;
}

/** 合法值定位原下标；缺失或非法时定位到当前年份减 20 岁。 */
function birthYearIndex(value, options, now = new Date()) {
  const list = Array.isArray(options) ? options : [];
  if (!list.length) return 0;
  const target = normalizeBirthYear(value, now) || defaultTongueProfile(now).birthYear;
  const index = list.indexOf(target);
  return index >= 0 ? index : 0;
}

/** 生日字符串（yyyy-MM-dd）→ 出生年。 */
function birthYearFromBirthday(birthday, now = new Date()) {
  const matched = String(birthday || '')
    .trim()
    .match(/^(\d{4})-\d{2}-\d{2}$/);
  return matched ? normalizeBirthYear(matched[1], now) : '';
}

/** 出生年 → 年龄（周岁）。 */
function ageFromBirthYear(birthYear, now = new Date()) {
  const year = Number(normalizeBirthYear(birthYear, now));
  if (!year) return '';
  const age = now.getFullYear() - year;
  return age >= 0 && age <= 150 ? age : '';
}

/** 默认资料：男、当前年份减 20 岁。 */
function defaultTongueProfile(now = new Date()) {
  return {
    gender: DEFAULT_GENDER,
    birthYear: String(now.getFullYear() - DEFAULT_AGE)
  };
}

/** 缓存值 → 合法资料；缺少或损坏时返回 null。 */
function normalizeTongueProfile(profile, now = new Date()) {
  const source = profile && typeof profile === 'object' ? profile : {};
  const gender = normalizeGender(source.gender);
  const birthYear = normalizeBirthYear(source.birthYear, now);
  return gender && birthYear ? { gender, birthYear } : null;
}

/** 读取最近一次确认过的舌诊资料。 */
function getCachedTongueProfile(now = new Date()) {
  return normalizeTongueProfile(readStorage(TONGUE_PROFILE_STORAGE_KEY), now);
}

/** 保存用户确认过的舌诊资料；仅写本地缓存，不触碰用户资料接口。 */
function saveTongueProfile(profile, now = new Date()) {
  const normalized = normalizeTongueProfile(profile, now);
  if (!normalized) return null;
  writeStorage(TONGUE_PROFILE_STORAGE_KEY, normalized);
  return normalized;
}

/**
 * 解析本次测试要使用的资料。
 *
 * 优先级：合法缓存 -> /auth/me -> 字段级默认值。
 * 接口失败也静默回落默认值，避免无法进入测试。
 */
function resolveTongueProfile(options) {
  const opts = options || {};
  const now = opts.now || new Date();
  const cached = getCachedTongueProfile(now);
  if (cached) return Promise.resolve(cached);

  const fallback = defaultTongueProfile(now);
  const fetchMe = opts.fetchMe || api.fetchMe;
  return Promise.resolve()
    .then(() => fetchMe(true))
    .then(user => ({
      gender: normalizeGender(user && user.gender) || fallback.gender,
      birthYear: birthYearFromBirthday(user && user.birthday, now) || fallback.birthYear
    }))
    .catch(() => fallback);
}

/** 性别 + 出生年都齐全才算资料完整。 */
function hasTongueProfile(profile) {
  return Boolean(profile && profile.gender && profile.birthYear);
}

module.exports = {
  DEFAULT_AGE,
  DEFAULT_GENDER,
  MIN_BIRTH_YEAR,
  TONGUE_PROFILE_STORAGE_KEY,
  ageFromBirthYear,
  birthYearFromBirthday,
  birthYearIndex,
  birthYearOptions,
  defaultTongueProfile,
  getCachedTongueProfile,
  hasTongueProfile,
  normalizeBirthYear,
  normalizeGender,
  normalizeTongueProfile,
  resolveTongueProfile,
  saveTongueProfile
};
