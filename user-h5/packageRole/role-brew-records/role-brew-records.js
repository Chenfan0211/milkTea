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

/** 搜索输入去抖时长：停止输入 300ms 后才查询，避免每敲一个字都发请求 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * 门店经营角色 · 冲泡记录（只读）。
 *
 * 口径与运营后台「冲泡记录」页完全一致：按**写入时刻归属门店**过滤，
 * 因此设备解绑或换绑后，历史记录仍显示当时所属门店的记录。
 *
 * 查询维度只有两个：时间范围（近 7 天 / 近 30 天）与关键词搜索框。
 * 关键词走服务端多字段模糊匹配（茶品名/编码、设备编码/ID/别名、客户代码、序列号），
 * 页面**不再调用「本店设备」接口** —— 原先那排设备筛选 chip 既要求设备接口，
 * 又因为横向 scroll-view 缺高度在 Skyline 下被压扁裁切。
 */
Page(
  withShare({
    data: {
      ready: false,
      title: '冲泡记录',
      storeName: '',
      ranges: STORE_BREW_RANGES,
      activeRangeId: '7d',
      keyword: '',
      hasFilter: false,
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
    onLoad() {
      ensureBusinessRole().then(() => this.syncRole());
    },
    onShow() {
      // 从其他页面返回时刷新一次（后台可能刚改过数据）
      if (this.loaded) this.reload();
    },
    onUnload() {
      if (this._searchTimer) clearTimeout(this._searchTimer);
    },
    /** 触底加载下一页（与 scroll-view 的 bindscrolltolower 绑定）。 */
    onReachBottom() {
      this.loadMore();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'store') {
        this.setData({ ready: false });
        wx.showToast({ title: '仅门店经营角色可查看冲泡记录', icon: 'none' });
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
          title: boundStore.name ? boundStore.name + '冲泡记录' : '冲泡记录'
        },
        () => this.reload()
      );
    },
    switchRange(event) {
      const id = event.currentTarget.dataset.id;
      if (!id || id === this.data.activeRangeId) return;
      this.setData({ activeRangeId: id }, () => this.reload());
    },
    /** 输入即搜：停止输入 300ms 后查询（防抖，避免逐字打请求）。 */
    handleKeyword(event) {
      const keyword = event.detail.value || '';
      this.setData({ keyword });
      if (this._searchTimer) clearTimeout(this._searchTimer);
      this._searchTimer = setTimeout(() => this.reload(), SEARCH_DEBOUNCE_MS);
    },
    /** 键盘「搜索」键：立即查询，不等防抖。 */
    confirmSearch() {
      if (this._searchTimer) clearTimeout(this._searchTimer);
      this.reload();
    },
    clearKeyword() {
      if (this._searchTimer) clearTimeout(this._searchTimer);
      this.setData({ keyword: '' }, () => this.reload());
    },
    /**
     * 从第 1 页重新加载（首次进入 / 刷新 / 切时间范围 / 改关键词）。
     *
     * <p>这里**不能用「loading 中直接 return」**：搜索是防抖触发的，用户在请求返回前继续输入时，
     * 新条件会被这层守卫吞掉，表现为「最后一次输入查不出来」。
     * 改为请求序号：只接受最后一次请求的结果，过期响应直接丢弃。
     */
    reload() {
      return this.setData({ page: 1, refreshing: true, loadError: '' }, () => this.fetchPage({ append: false }));
    },
    /** 刷新按钮与下拉刷新共用入口。 */
    refresh() {
      return this.reload();
    },
    fetchPage(options) {
      const append = Boolean(options && options.append);
      const page = append ? this.data.page + 1 : 1;
      const seq = (this._reqSeq || 0) + 1;
      this._reqSeq = seq;
      if (!append) this.setData({ loading: true });
      return loadStoreBrewRecords({
        page,
        size: this.data.size,
        rangeId: this.data.activeRangeId,
        keyword: this.data.keyword
      })
        .then(result => {
          // 过期响应（用户已改了关键词/时间范围）必须丢弃，否则会把旧结果盖在新条件上
          if (seq !== this._reqSeq) return;
          if (!result) {
            this.setData({ ready: false, records: [], loading: false, refreshing: false });
            this.leaveToRoleCenter();
            return;
          }
          const records = append ? this.data.records.concat(result.records) : result.records;
          this.loaded = true;
          this.setData({
            records,
            total: result.total,
            page: result.current,
            hasMore: records.length < result.total,
            rangeText: result.range.from + ' 至 ' + result.range.to,
            hasFilter: Boolean(this.data.keyword),
            loading: false,
            loadingMore: false,
            refreshing: false,
            loadError: ''
          });
        })
        .catch(() => {
          if (seq !== this._reqSeq) return;
          this.setData({
            loading: false,
            loadingMore: false,
            refreshing: false,
            loadError: '冲泡记录加载失败，请点击重试'
          });
        });
    },
    loadMore() {
      if (!this.data.hasMore || this.data.loading || this.data.loadingMore) return Promise.resolve();
      this.setData({ loadingMore: true });
      return this.fetchPage({ append: true });
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
