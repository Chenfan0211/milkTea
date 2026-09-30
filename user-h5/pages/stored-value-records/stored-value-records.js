const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { formatDateTime } = require('../../utils/date-format');
const { getUserProfile, roundMoney } = require('../../utils/user-profile');

const PAGE_SIZE = 20;

/** 后端 StoredValueRecordDTO -> 页面展示结构 */
function normalizeRecord(record, index) {
  const type = String((record && record.type) || '').toUpperCase();
  const amountValue = Number((record && record.amount) || 0) / 100;
  const isIncome = type === 'RECHARGE' || type === 'REFUND';
  const title = (record && record.title) || (isIncome ? '储值充值' : '余额支付');
  const amountText = (isIncome ? '+' : '-') + amountValue.toFixed(2);
  const bizNo = String((record && record.bizNo) || '');
  return {
    key: String((record && record.id) || bizNo || index) + '-' + type + '-' + index,
    title,
    time: formatDateTime((record && record.time) || ''),
    amountText,
    isIncome,
    bizText: bizNo ? '单号 ' + bizNo : ''
  };
}

Page(
  withShare({
    data: {
      balanceText: '0',
      records: [],
      page: 1,
      hasMore: true,
      loadingMore: false
    },
    onLoad() {
      this.syncBalance();
      this.loadRecords();
    },
    onShow() {
      this.syncBalance();
    },
    syncBalance() {
      const render = () => this.setData({ balanceText: roundMoney(Number(getUserProfile().balance) || 0).toFixed(2) });
      render();
    },
    loadRecords() {
      this.setData({ page: 1, hasMore: true, loadingMore: false });
      api
        .fetchStoredValueRecords(1, PAGE_SIZE)
        .then(res => {
          const records = (res && Array.isArray(res.records) ? res.records : []).map(normalizeRecord);
          const total = Number((res && res.total) || 0);
          this.setData({
            records,
            hasMore: records.length < total,
            page: 1
          });
        })
        .catch(() => {
          this.setData({ records: [], hasMore: false });
          wx.showToast({ title: '储值记录加载失败，请稍后重试', icon: 'none' });
        });
    },
    onReachBottom() {
      if (!this.data.hasMore || this.data.loadingMore) return;
      const nextPage = this.data.page + 1;
      this.setData({ loadingMore: true });
      api
        .fetchStoredValueRecords(nextPage, PAGE_SIZE)
        .then(res => {
          const batch = (res && Array.isArray(res.records) ? res.records : []).map((item, i) =>
            normalizeRecord(item, this.data.records.length + i)
          );
          const total = Number((res && res.total) || 0);
          const records = this.data.records.concat(batch);
          this.setData({
            records,
            page: nextPage,
            hasMore: records.length < total,
            loadingMore: false
          });
        })
        .catch(() => {
          this.setData({ loadingMore: false });
          wx.showToast({ title: '加载更多失败，请重试', icon: 'none' });
        });
    }
  })
);
