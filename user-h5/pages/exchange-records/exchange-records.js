const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { exchangeRecordCategories } = require('../../data/mock');

Page(
  withShare({
    data: {
      exchangeRecordCategories,
      activeCategory: 'all',
      records: [],
      filteredRecords: [],
      loading: true
    },
    onShow() {
      this.refreshRecords();
    },
    /**
     * 兑换记录以后端为唯一数据源。
     *
     * 修复说明：原实现调用 fetchExchangeOrders() 后，紧接着又从
     * app.globalData.exchangeRecords（本地旧数据）读取并渲染，
     * 接口结果被立即覆盖 —— 表现为「兑换成功但记录页看不到」，
     * 且切换页签同样只读本地数据。现统一以接口返回为准。
     */
    refreshRecords() {
      this.setData({ loading: true });
      return api
        .fetchExchangeOrders()
        .then(list => {
          const records = (Array.isArray(list) ? list : []).map(this.normalizeRecord);
          this.setData({ records, loading: false });
          this.applyFilter(this.data.activeCategory, records);
        })
        .catch(() => {
          // 接口失败时明确置空并提示，避免展示来源不明的旧数据
          this.setData({ records: [], filteredRecords: [], loading: false });
          wx.showToast({ title: '兑换记录加载失败，请稍后重试', icon: 'none' });
        });
    },
    /** 后端 ExchangeOrder -> 页面展示结构（字段缺失时兜底，避免渲染空白） */
    normalizeRecord(item) {
      const record = item && typeof item === 'object' ? item : {};
      return {
        id: String(record.id == null ? record.exchangeNo || '' : record.id),
        exchangeNo: record.exchangeNo || '',
        name: record.name || record.productName || record.exchangeNo || '时光币兑换',
        pickupCode: record.pickupCode || '',
        status: record.status || '',
        points: Number(record.points) || 0,
        time: record.createTime || '',
        statusLabel: this.statusText(record.status)
      };
    },
    applyFilter(id, records) {
      const source = records || [];
      const filteredRecords = id === 'all' ? source : source.filter(item => item.status === id);
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
      const value = String(status || '');
      return map[value] || map[value.toUpperCase()] || value;
    },
    switchCategory(event) {
      const { id } = event.currentTarget.dataset;
      this.applyFilter(id, this.data.records);
    }
  })
);

