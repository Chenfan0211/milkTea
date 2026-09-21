const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { exchangeRecordCategories, exchangeRecords: mockExchangeRecords } = require('../../data/mock');

Page(
  withShare({
    data: {
      exchangeRecordCategories,
      activeCategory: 'all',
      filteredRecords: mockExchangeRecords
    },
    onShow() {
      // 兑换记录从后端拉取
      api
        .fetchExchangeOrders()
        .then(list => {
          if (Array.isArray(list)) this.setData({ records: list });
        })
        .catch(() => null);      const app = getApp();
      const records = app.globalData.exchangeRecords || mockExchangeRecords;
      this.applyFilter(this.data.activeCategory, records);
    },
    applyFilter(id, records) {
      const withLabel = (records || []).map(item =>
        Object.assign({}, item, {
          statusLabel: this.statusText(item.status)
        })
      );
      const filteredRecords = id === 'all' ? withLabel : withLabel.filter(item => item.status === id);
      this.setData({ activeCategory: id, filteredRecords });
    },
    statusText(status) {
      const map = {
        pending_payment: '待支付',
        pending_delivery: '待发货',
        pending_receipt: '待收货',
        pending_verify: '待核销',
        verified: '已核销',
        completed: '已完成'
      };
      return map[status] || status || '';
    },
    switchCategory(event) {
      const { id } = event.currentTarget.dataset;
      const app = getApp();
      const records = app.globalData.exchangeRecords || mockExchangeRecords;
      this.applyFilter(id, records);
    }
  })
);

