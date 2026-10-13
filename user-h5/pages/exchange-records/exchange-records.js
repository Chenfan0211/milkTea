const { withShare } = require('../../utils/share');
const loginGuard = require('../../utils/login-guard');
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
      // 兑换记录需登录：未登录时先引导登录，登录后再拉取。
      loginGuard.requireLogin(() => this.refreshRecords(), { reason: '登录后可查看兑换记录' });
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
    /**
     * 后端 ExchangeOrderView -> 页面展示结构（字段缺失时兜底，避免渲染空白）。
     *
     * 注意：name 不再回退到 exchangeNo。原实现把「兑换单号」当商品名兜底，
     * 后端当时又没返回商品字段，导致卡片主标题显示成一串 CZ 单号。
     * 现在商品名由后端联表 points_product 提供，缺失时给统一兜底文案。
     */
    normalizeRecord(item) {
      const record = item && typeof item === 'object' ? item : {};
      return {
        id: String(record.id == null ? record.exchangeNo || '' : record.id),
        exchangeNo: record.exchangeNo || '',
        name: record.productName || record.name || '时光币兑换',
        image: record.productImage || '',
        quantity: Number(record.quantity) || 1,
        pickupCode: record.pickupCode || '',
        status: String(record.status || '').toLowerCase(),
        points: Number(record.points) || 0,
        time: record.createTime || '',
        timeText: this.formatTime(record.createTime),
        statusLabel: this.statusText(record.status)
      };
    },
    /**
     * 兑换时间展示：后端下发 ISO（2026-10-13T10:22:31）不适合直接给用户看，
     * 这里统一裁剪为「yyyy-MM-dd HH:mm」；解析失败时原样返回（不显示 undefined）。
     */
    formatTime(value) {
      if (!value) return '';
      const text = String(value);
      const matched = text.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})/);
      if (matched) return matched[1] + ' ' + matched[2];
      return text.length >= 16 ? text.slice(0, 16).replace('T', ' ') : text;
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
      return map[value.toLowerCase()] || value;
    },
    switchCategory(event) {
      const { id } = event.currentTarget.dataset;
      this.applyFilter(id, this.data.records);
    }
  })
);
