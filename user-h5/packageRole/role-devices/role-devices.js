const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const {
  getBoundStore,
  getCurrentBusinessRole,
  STORE_DEVICE_PAGE_SIZE,
  loadStoreDeviceSummary,
  loadStoreDevices
} = require('../../utils/roles');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const DEVICE_BREW_URL = '/packageRole/role-device-brew/role-device-brew';

/**
 * 门店经营角色 · 设备管理（只读）。
 *
 * 只展示「当前绑定在本店名下」的设备：设备台账由运营在后台建档与绑定，
 * 门店侧不提供新增 / 绑定 / 解绑（越权与错绑的代价都由后台统一承担）。
 * 在线状态与最近上报时间来自设备队列上报，进入页面自动拉取，可下拉或点「刷新」重取。
 */
Page(
  withShare({
    data: {
      ready: false,
      title: '本店设备',
      storeName: '',
      devices: [],
      total: 0,
      online: 0,
      page: 1,
      size: STORE_DEVICE_PAGE_SIZE,
      hasMore: false,
      loading: false,
      loadingMore: false,
      refreshing: false,
      loadError: '',
      updatedLabel: ''
    },
    onLoad() {
      // 冷启动 / 分享进入时本地还没有角色，先确保角色已同步再判权限
      ensureBusinessRole().then(() => this.syncRole());
    },
    onShow() {
      // 从其他页面返回时刷新一次（设备可能刚被运营绑定或上报状态）
      if (this.loaded) this.reload();
    },
    /** 触底加载下一页（与 scroll-view 的 bindscrolltolower 绑定）。 */
    onReachBottom() {
      this.loadMore();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'store') {
        this.setData({ ready: false });
        wx.showToast({ title: '仅门店经营角色可查看设备', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const boundStore = getBoundStore(role.id);
      if (!boundStore) {
        this.setData({ ready: false });
        wx.showToast({ title: '未获取到门店信息，请重新选择角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData(
        {
          ready: true,
          storeName: boundStore.name || '',
          title: boundStore.name ? boundStore.name + '设备' : '本店设备'
        },
        () => this.reload()
      );
    },
    /** 从第 1 页重新加载（首次进入 / 刷新 / 返回本页）。 */
    reload() {
      if (this.data.loading) return Promise.resolve();
      return this.setData({ page: 1, refreshing: true, loadError: '' }, () => this.fetchPage({ append: false }));
    },
    /** 刷新按钮与下拉刷新共用入口。 */
    refresh() {
      return this.reload();
    },
    fetchPage(options) {
      const append = Boolean(options && options.append);
      const page = append ? this.data.page + 1 : 1;
      if (!append) this.setData({ loading: true });
      return Promise.all([
        loadStoreDevices({ page, size: this.data.size }),
        append ? Promise.resolve(null) : loadStoreDeviceSummary()
      ])
        .then(([result, summary]) => {
          if (!result) {
            // 角色在请求过程中被切换 / 失效：按无权限处理，不渲染假数据
            this.setData({ ready: false, devices: [], loading: false, refreshing: false });
            this.leaveToRoleCenter();
            return;
          }
          const devices = append ? this.data.devices.concat(result.records) : result.records;
          this.loaded = true;
          this.setData({
            devices,
            total: summary ? summary.total : result.total,
            online: summary ? summary.online : this.data.online,
            page: result.current,
            hasMore: devices.length < result.total,
            loading: false,
            loadingMore: false,
            refreshing: false,
            loadError: '',
            updatedLabel: this.currentTimeLabel()
          });
        })
        .catch(() => {
          this.setData({
            loading: false,
            loadingMore: false,
            refreshing: false,
            loadError: '设备加载失败，请点击重试'
          });
        });
    },
    loadMore() {
      if (!this.data.hasMore || this.data.loading || this.data.loadingMore) return Promise.resolve();
      this.setData({ loadingMore: true });
      return this.fetchPage({ append: true });
    },
    /** 头部「更新于 hh:mm」：用拉取时刻生成，避免页面显示旧时间。 */
    currentTimeLabel() {
      const now = new Date();
      const pad = value => String(value).padStart(2, '0');
      return pad(now.getHours()) + ':' + pad(now.getMinutes());
    },
    /** 设备卡片进入单设备冲泡明细；设备编码是记录筛选主键。 */
    openDeviceBrew(event) {
      const device = event.currentTarget.dataset;
      if (!device || !device.deviceSn) return;
      const params = [
        'deviceSn=' + encodeURIComponent(device.deviceSn),
        'deviceName=' + encodeURIComponent(device.deviceName || ''),
        'displayName=' + encodeURIComponent(device.displayName || ''),
        'statusKey=' + encodeURIComponent(device.statusKey || ''),
        'statusLabel=' + encodeURIComponent(device.statusLabel || ''),
        'lastReportTime=' + encodeURIComponent(device.lastReportTime || '')
      ].join('&');
      wx.navigateTo({ url: DEVICE_BREW_URL + '?' + params });
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
