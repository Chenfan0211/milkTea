const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const {
  getBoundStore,
  getCurrentBusinessRole,
  STORE_BREW_RANGES,
  STORE_DEVICE_PAGE_SIZE,
  loadStoreBrewRecords
} = require('../../utils/roles');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

/** URL 参数可能仍带 encodeURIComponent 编码；解码失败时保留原值，避免单个脏参数阻断页面。 */
function safeDecodeOption(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  try {
    return decodeURIComponent(raw);
  } catch (error) {
    return raw;
  }
}

/**
 * 门店经营角色 · 单设备冲泡明细（只读）。
 *
 * 从「本店设备」进入，固定按设备编码筛选；后端继续按当前登录用户所属门店校验，
 * 避免通过改参数读取其他门店或设备的数据。
 */
Page(
  withShare({
    data: {
      ready: false,
      title: '设备冲泡明细',
      device: null,
      ranges: STORE_BREW_RANGES,
      activeRangeId: '7d',
      rangeText: '',
      records: [],
      total: 0,
      page: 1,
      size: STORE_DEVICE_PAGE_SIZE,
      hasMore: false,
      loading: false,
      loadingMore: false,
      refreshing: false,
      loadError: ''
    },
    onLoad(options) {
      const device = this.parseDevice(options);
      if (!device) {
        wx.showToast({ title: '设备信息缺失', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData({ device }, () => {
        ensureBusinessRole().then(() => this.syncRole());
      });
    },
    onReachBottom() {
      this.loadMore();
    },
    switchRange(event) {
      const id = event.currentTarget.dataset.id;
      if (!id || id === this.data.activeRangeId) return;
      this.setData({ activeRangeId: id }, () => this.reload());
    },
    reload() {
      const seq = (this._reqSeq || 0) + 1;
      this._reqSeq = seq;
      return this.setData({ page: 1, refreshing: true, loadingMore: false, loadError: '' }, () =>
        this.fetchPage({ append: false, seq })
      );
    },
    refresh() {
      return this.reload();
    },
    fetchPage(options) {
      const append = Boolean(options && options.append);
      const page = append ? this.data.page + 1 : 1;
      const seq = append ? (options && options.seq) || this._reqSeq : this._reqSeq;
      if (!append) this.setData({ loading: true });
      return loadStoreBrewRecords({
        page,
        size: this.data.size,
        rangeId: this.data.activeRangeId,
        deviceSn: this.data.device && this.data.device.deviceSn
      })
        .then(result => {
          if (seq !== this._reqSeq) {
            this.setData({ loadingMore: false });
            return;
          }
          if (!result) {
            this.setData({ ready: false, records: [], loading: false, refreshing: false });
            this.leaveToRoleCenter();
            return;
          }
          const records = append ? this.data.records.concat(result.records) : result.records;
          this.setData({
            records,
            total: result.total,
            page: result.current,
            hasMore: records.length < result.total,
            rangeText: result.range.from + ' 至 ' + result.range.to,
            loading: false,
            loadingMore: false,
            refreshing: false,
            loadError: ''
          });
        })
        .catch(() => {
          if (seq !== this._reqSeq) {
            this.setData({ loadingMore: false });
            return;
          }
          this.setData({
            loading: false,
            loadingMore: false,
            refreshing: false,
            loadError: '冲泡明细加载失败，请点击重试'
          });
        });
    },
    loadMore() {
      if (!this.data.hasMore || this.data.loading || this.data.loadingMore || this.data.refreshing)
        return Promise.resolve();
      this.setData({ loadingMore: true });
      return this.fetchPage({ append: true, seq: this._reqSeq });
    },
    parseDevice(options) {
      const source = options || {};
      const deviceSn = safeDecodeOption(source.deviceSn);
      if (!deviceSn) return null;
      const status = safeDecodeOption(source.statusKey).toLowerCase();
      return {
        deviceSn,
        deviceName: safeDecodeOption(source.deviceName),
        displayName: safeDecodeOption(source.displayName) || deviceSn,
        statusKey: ['online', 'offline', 'unknown'].includes(status) ? status : 'unknown',
        statusLabel: safeDecodeOption(source.statusLabel) || '未知',
        lastReportTime: safeDecodeOption(source.lastReportTime)
      };
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    }
  })
);
