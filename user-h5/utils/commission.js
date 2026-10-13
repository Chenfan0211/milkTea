/**
 * 投资人「月度分佣」前端归一化与计算。
 *
 * 后端返回金额均为「分」，本模块负责：
 *   - 分 -> 元文本（复用 utils/money.formatFen，去尾零）；
 *   - 万分比 -> 百分比文案（1500 -> "15%"）；
 *   - 达标进度百分比（0-100 封顶）。
 */
const { formatFen } = require('./money');

/** 万分比 -> 百分比文本（1500 -> "15%"，2000 -> "20%"） */
function ratioToPercent(ratio) {
  const n = Number(ratio) || 0;
  const value = n / 100;
  return (Number.isInteger(value) ? String(value) : String(value)) + '%';
}

/** 分 -> 元展示文本（带 ¥ 前缀，保留两位小数） */
function fenToYuanText(fen) {
  const value = (Number(fen) || 0) / 100;
  return '¥' + value.toFixed(2);
}

/**
 * 把后端 InvestorCommissionView 归一化为页面展示结构。
 * 字段缺失全部兜底，避免渲染空白或 NaN。
 */
function normalizeCommission(view) {
  const v = view && typeof view === 'object' ? view : {};
  const rule = v.rule || {};
  const current = v.current || {};
  const progress = v.progress || {};

  const thresholdEnabled = Boolean(rule.thresholdEnabled);
  const reached = Boolean(current.reached);

  return {
    month: v.month || '',
    // 规则
    rule: {
      basePercent: ratioToPercent(rule.investorRatio),
      afterPercent: ratioToPercent(rule.investorRatioAfter),
      thresholdText: fenToYuanText(rule.investorThresholdAmount),
      thresholdEnabled
    },
    // 本月汇总
    current: {
      commissionText: fenToYuanText(current.commissionAmount),
      salesText: fenToYuanText(current.salesAmount),
      settledText: fenToYuanText(current.settledAmount),
      pendingText: fenToYuanText(current.pendingAmount),
      effectivePercent: ratioToPercent(current.effectiveRatio),
      reached
    },
    // 进度
    progress: {
      percent: clampPercent(Number(progress.percent)),
      remainText: fenToYuanText(progress.remainToThreshold),
      reached
    },
    // 门店汇总
    stores: (Array.isArray(v.stores) ? v.stores : []).map(s => ({
      id: s.id,
      name: s.name || '未命名门店',
      salesText: fenToYuanText(s.salesAmount),
      commissionText: fenToYuanText(s.commissionAmount),
      orderCount: Number(s.orderCount) || 0
    })),
    // 近 12 个月
    records: (Array.isArray(v.records) ? v.records : []).map(r => ({
      month: r.month || '',
      commissionText: fenToYuanText(r.commissionAmount),
      salesText: fenToYuanText(r.salesAmount),
      effectivePercent: ratioToPercent(r.effectiveRatio),
      reached: Boolean(r.reached)
    }))
  };
}

function clampPercent(n) {
  const value = Number(n);
  if (!Number.isFinite(value) || value <= 0) return 0;
  return Math.min(100, Math.round(value));
}

module.exports = {
  ratioToPercent,
  fenToYuanText,
  normalizeCommission
};
