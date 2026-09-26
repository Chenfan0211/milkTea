const api = require('./api');

/**
 * 会员等级统一入口：等级缓存 + 成长值 / 等级进度 + 会员价折扣计算。
 *
 * 阶段 C 后会员等级来自 /api/v1/app/member-levels：
 * - 等级展示：页面在 onShow 先 await refreshMemberLevelsFromRemote() 再调用 buildLevelMeta()；
 * - 会员价：调用 calcMemberPrice(原价, 用户 vipLevel)，未就绪时会退回原价（无折扣）。
 *
 * 说明：等级缓存为进程内唯一来源，价格与成长值共用同一份数据，避免重复请求。
 */

let memberLevels = [];

/** 用远端数据刷新会员等级缓存。 */
function refreshMemberLevelsFromRemote() {
  return api
    .fetchMemberLevels()
    .then(list => {
      if (Array.isArray(list)) memberLevels = normalizeLevels(list);
      return memberLevels;
    })
    .catch(() => memberLevels);
}

/** 当前会员等级缓存（只读）。 */
function getMemberLevels() {
  return memberLevels;
}

/**
 * 直接注入等级数据（同步）。
 * 供测试与已持有等级数据的场景使用，避免依赖异步请求。
 */
function setMemberLevels(list) {
  memberLevels = normalizeLevels(list || []);
  return memberLevels;
}

/** 后端金额单位为「分」，门槛换算为「元」。 */
function normalizeLevels(list) {
  return list.map(item => ({
    level: item.levelCode || item.level || '',
    name: item.name || '',
    amountTarget: Math.round((Number(item.amountTarget) || 0) / 100),
    condition: item.condition || (Number(item.amountTarget) ? `累计消费满${Math.round(Number(item.amountTarget) / 100)}元` : '注册即得'),
    discount: item.discount || '',
    discountText: formatDiscountText(item.discount),
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

// 成长值只由下单实付金额累积：1 元 = 1 成长值。时光币与成长值同额发放，但两者相互独立。
function resolveLevelIndex(totalSpend) {
  let index = 0;
  for (let i = 0; i < memberLevels.length; i += 1) {
    if (totalSpend >= memberLevels[i].amountTarget) index = i;
  }
  return index;
}

function mutedIconFor(icon) {
  if (!icon) return '';
  return icon.endsWith('-brand') ? icon.replace('-brand', '-muted') : icon + '-muted';
}

function buildLevelMeta(profile) {
  if (!memberLevels.length) {
    return {
      totalSpend: Number(profile && profile.totalSpend) || 0,
      currentGrowth: Math.floor(Number(profile && profile.totalSpend) || 0),
      currentLevel: '',
      currentName: '',
      currentDiscount: '',
      currentIndex: 0,
      nextName: '',
      nextLevel: '',
      progressPercent: 0,
      progressLabel: '',
      progressCurrent: 0,
      progressTarget: 0,
      levels: [],
      axis: [],
      privileges: [],
      privilegesTitle: ''
    };
  }

  const totalSpend = Number(profile.totalSpend) || 0;
  const growth = Math.floor(totalSpend);
  const currentIndex = resolveLevelIndex(totalSpend);
  const current = memberLevels[currentIndex];
  const next = memberLevels[currentIndex + 1] || null;

  const levels = memberLevels.map((item, index) => ({
    index,
    level: item.level,
    name: item.name,
    amountTarget: item.amountTarget,
    condition: item.condition,
    discount: item.discount,
    discountText: item.discountText,
    benefits: item.benefits.map(benefit => Object.assign({}, benefit, { mutedIcon: mutedIconFor(benefit.icon) })),
    isCurrent: index === currentIndex,
    isReached: index <= currentIndex
  }));

  const axis = memberLevels.map((item, index) => ({
    index,
    level: item.level,
    value: item.amountTarget,
    reached: index <= currentIndex
  }));

  let progressPercent = 0;
  let progressLabel = '';
  const progressCurrent = growth;
  const progressTarget = next ? next.amountTarget : growth || 1;
  if (next) {
    const remaining = Math.max(0, next.amountTarget - totalSpend);
    progressPercent = Math.min(100, Math.round((growth / next.amountTarget) * 100));
    progressLabel = '再消费 ' + remaining + ' 元升级';
  } else {
    progressPercent = 100;
    progressLabel = '已是最高等级';
  }

  const privileges = levels[currentIndex].benefits;

  return {
    totalSpend,
    currentGrowth: growth,
    currentLevel: current.level,
    currentName: current.name,
    currentDiscount: current.discount,
    currentDiscountText: current.discountText,
    currentIndex,
    nextName: next ? next.name : '',
    nextLevel: next ? next.level : '',
    progressPercent,
    progressLabel,
    progressCurrent,
    progressTarget,
    levels,
    axis,
    privileges,
    privilegesTitle: current.name + ' 会员特权'
  };
}

function roundMoney(value, digits = 2) {
  const factor = Math.pow(10, digits);
  return Math.round(value * factor) / factor;
}

/**
 * 将支持的折扣写法归一化为「支付比例百分比」。
 * 80 / 80% -> 80；8折 -> 80；历史 0.8 -> 80；无法识别返回 null。
 */
function normalizeDiscountPercent(discountText) {
  if (discountText == null) return null;
  const text = String(discountText).trim();
  if (!text) return null;

  const zheMatch = text.match(/([-+]?\d+(?:\.\d+)?)\s*折/);
  if (zheMatch) {
    const zhe = Number(zheMatch[1]);
    return zhe > 0 && zhe <= 10 ? roundMoney(zhe * 10, 2) : null;
  }

  const percentMatch = text.match(/([-+]?\d+(?:\.\d+)?)\s*%/);
  if (percentMatch) {
    const percent = Number(percentMatch[1]);
    return percent > 0 && percent <= 100 ? roundMoney(percent, 2) : null;
  }

  const num = Number(text);
  if (!Number.isFinite(num) || num <= 0) return null;
  // 历史小数比例：0.8 表示支付原价的 80%。
  if (num < 1) return roundMoney(num * 100, 2);
  // 新规范：1~100 的整数百分比；1 表示 1%，100 表示 100%。
  if (Number.isInteger(num) && num <= 100) return num;
  return null;
}

/** '80' / '8折' -> 0.8；无法解析时返回 1（不打折）。 */
function parseDiscount(discountText) {
  const percent = normalizeDiscountPercent(discountText);
  return percent == null ? 1 : roundMoney(percent / 100, 4);
}

/** 等级说明展示统一为百分比；无效值显示「无折扣」。 */
function formatDiscountText(discountText) {
  const percent = normalizeDiscountPercent(discountText);
  return percent == null ? '无折扣' : `${percent}%`;
}

/**
 * 按「等级代码或等级名称」匹配等级对象；未匹配到时返回不打折的「普通」等级。
 *
 * 为什么两种都要认（历史 bug）：
 *   后端 app_user.vip_level 写入的是等级**代码**（MiniAppAuthService 里 setVipLevel("Lv1")），
 *   而等级列表里 name 是**名称**（"时光卡"）、level 才是代码（"Lv1"）。
 *   旧实现只比 name，导致 "Lv1" 永远匹配不到 -> 回退兜底等级 discount=1 -> 会员价恒等于原价，
 *   表现为「等级显示正常，但会员价从不打折」。
 *   这里同时接受两种写法：后端无论存代码还是名称都能正确取到折扣。
 */
function getUserLevel(vipLevel) {
  const key = String(vipLevel || '');
  const level = memberLevels.find(item => item.name === key || item.level === key);
  return level || { name: '普通', discount: '100', level: '' };
}

/**
 * 会员价 = 门市价（原价）× 当前用户等级折扣。
 *
 * listPrice 为「元」，取自商品原价（product-card 与 spec-sheet 均以 originalPrice 为基数）；
 * vipLevel 取自用户资料的 vipLevel，代码（Lv1）或名称（时光卡）均可。
 */
function calcMemberPrice(listPrice, vipLevel) {
  const level = getUserLevel(vipLevel);
  const discount = parseDiscount(level.discount);
  return roundMoney(Number(listPrice || 0) * discount);
}

module.exports = {
  refreshMemberLevelsFromRemote,
  getMemberLevels,
  setMemberLevels,
  normalizeLevels,
  buildLevelMeta,
  resolveLevelIndex,
  roundMoney,
  normalizeDiscountPercent,
  parseDiscount,
  formatDiscountText,
  getUserLevel,
  calcMemberPrice
};
