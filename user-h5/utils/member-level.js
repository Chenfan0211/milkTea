const api = require('./api');

/**
 * 会员等级成长值计算。
 *
 * 阶段 C 后会员等级来自 /api/v1/app/member-levels：
 * 页面在 onShow 先 await refreshMemberLevelsFromRemote() 再调用 buildLevelMeta()。
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

module.exports = {
  refreshMemberLevelsFromRemote,
  getMemberLevels,
  setMemberLevels,
  normalizeLevels,
  buildLevelMeta,
  resolveLevelIndex
};
