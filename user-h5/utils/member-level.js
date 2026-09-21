const { memberLevels } = require('../data/mock');

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
  let progressCurrent = growth;
  let progressTarget = next ? next.amountTarget : growth || 1;
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
  buildLevelMeta,
  resolveLevelIndex
};
