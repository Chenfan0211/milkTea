const { withShare } = require('../../utils/share');
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
    return Object.assign({}, record, { date: formatDateTime(record.date), amount: String(record.amount) });
  }
  const amountValue = Number(record.amount) || 0;
  const source = String(record.source || '');
  const remark = String(record.remark || '');
  return {
    id: String(record.id),
    title: remark || SOURCE_LABELS[source] || '时光币变动',
    date: formatDateTime(record.createTime),
    amount: (amountValue > 0 ? '+' : '') + amountValue,
    source: SOURCE_LABELS[source] || remark || source || '时光币'
  };
}

Page(
  withShare({
    data: {
      pointsRecords: []
    },
    onShow() {
      const localRecords = (getApp().globalData.pointsRecords || [])
        .map(normalizeRecord)
        .filter(Boolean);
      this.setData({ pointsRecords: localRecords });
      api
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
