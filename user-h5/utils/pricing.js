const { userProfile, memberLevels } = require('../data/mock');

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
  const name = String(vipLevelName || userProfile.vipLevel || '');
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
  roundMoney,
  parseDiscount,
  getUserLevel,
  calcMemberPrice
};
