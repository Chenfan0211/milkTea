const { withShare } = require('../../utils/share');
const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { formatDateTime } = require('../../utils/date-format');

const SOURCE_LABELS = {
  signin: '每日签到',
  exchange: '时光币兑换',
  order: '订单奖励',
  refund: '退款返还'
};

/** 后端 PointsRecord -> 页面展示结构；同时兼容本地 mock 旧字段。 */
function normalizeRecord(record) {
  if (!record || typeof record !== 'object') return null;
  if (record.title && record.date) {
    const amount = record.amount == null ? '' : String(record.amount);
    return Object.assign({}, record, { date: formatDateTime(record.date), amount, isNegative: amount.startsWith('-') });
  }
  const amountValue = Number(record.amount) || 0;
  const source = String(record.source || '');
  const remark = String(record.remark || '');
  return {
    id: String(record.id),
    title: remark || SOURCE_LABELS[source] || '时光币变动',
    date: formatDateTime(record.createTime),
    amount: (amountValue > 0 ? '+' : '') + amountValue,
    isNegative: amountValue < 0,
    source: SOURCE_LABELS[source] || remark || source || '时光币'
  };
}

Page(
  withShare({
    data: {
      pointsRecords: []
    },
    onShow() {
      const localRecords = (getApp().globalData.pointsRecords || []).map(normalizeRecord).filter(Boolean);
      this.setData({ pointsRecords: localRecords });
      // 积分记录需登录：未登录时先引导登录，登录后再拉取，
      // 避免未登录用户看到「登录已失效」而非友好的「请先登录」引导。
      loginGuard.requireLogin(() => this.loadPointsRecords(), { reason: '登录后可查看时光币明细' });
    },
    loadPointsRecords() {
      return api
        .fetchPointsRecords()
        .then(list => {
          if (Array.isArray(list) && list.length) {
            this.setData({ pointsRecords: list.map(normalizeRecord).filter(Boolean) });
          }
        })
        .catch(() => null);
    }
  })
);
