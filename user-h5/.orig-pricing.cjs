const api = require('./api');

/**
 * 会员等级价格计算。
 *
 * 阶段 C 后会员等级来自 /api/v1/app/member-levels：
 * 首次调用 calcMemberPrice / getUserLevel 前需 await refreshMemberLevelsFromRemote()，
 * 或由页面调用（内部做一次性懒加载缓存）。
 */

let memberLevels = [];
let loading = null;

/** 用远端数据刷新会员等级缓存（幂等，可重复调用）。 */
function refreshMemberLevelsFromRemote() {
  return api
    .fetchMemberLevels()
    .then(list => {
      if (Array.isArray(list)) memberLevels = normalizeLevels(list);
      return memberLevels;
    })
    .catch(() => memberLevels);
}

/** 懒加载：未拉取过则触发一次，避免页面忘记调用时等级为空。 */
function ensureLevels() {
  if (memberLevels.length) return Promise.resolve(memberLevels);
  if (!loading) loading = refreshMemberLevelsFromRemote().then(list => { loading = null; return list; });
  return loading;
}

/** 后端金额单位为「分」，等级门槛换算为「元」；折扣沿用后端 discount 文案。 */
function normalizeLevels(list) {
  return list.map(item => ({
    level: item.levelCode || item.level || '',
    name: item.name || '',
    amountTarget: Math.round((Number(item.amountTarget) || 0) / 100),
    condition: item.condition || (Number(item.amountTarget) ? `累计消费满${Math.round(Number(item.amountTarget) / 100)}元` : '注册即得'),
    discount: item.discount || '',
    benefits: parseBenefits(item.benefits)
  }));
}

function parseBenefits(raw) {
  if (Array.isArray(raw)) return raw;
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch (error) {
      return [];
    }
  }
  return [];
}

function roundMoney(value, digits = 2) {
  const factor = Math.pow(10, digits);
  return Math.round(value * factor) / factor;
}

/** '8折' -> 0.8 */
function parseDiscount(discountText) {
  if (discountText == null) return 1;
  const match = String(discountText).match(/(\d+(?:\.\d+)?)\s*折/);
  if (match) return roundMoney(Number(match[1]) / 10, 2);
  const num = Number(discountText);
  return Number.isFinite(num) && num > 0 && num <= 1 ? num : 1;
}

/** 根据会员等级名称匹配等级对象 */
function getUserLevel(vipLevelName) {
  const name = String(vipLevelName || '');
  const level = memberLevels.find(item => item.name === name);
  return level || { name: '普通', discount: 1, level: '' };
}

/**
 * 计算商品展示价格
 * listPrice: 门市价（原价 originalPrice）
 * 返回 memberPrice（会员价 = 原价 × 等级折扣）
 */
function calcMemberPrice(listPrice, vipLevelName) {
  const level = getUserLevel(vipLevelName);
  const discount = parseDiscount(level.discount);
  return roundMoney(Number(listPrice || 0) * discount);
}

module.exports = {
  refreshMemberLevelsFromRemote,
  ensureLevels,
  normalizeLevels,
  roundMoney,
  parseDiscount,
  getUserLevel,
  calcMemberPrice
};
