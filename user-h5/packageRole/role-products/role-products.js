const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getBoundStore, getCurrentSubjectId } = require('../../utils/roles');
const api = require('../../utils/api');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const DETAIL_URL = '/packageRole/role-product-detail/role-product-detail';
const STATUS_TABS = [
  { id: 'all', label: '全部' },
  { id: 'listed', label: '已上架' },
  { id: 'unlisted', label: '已下架' }
];

// 搜索同时匹配商品名称与商品编号，大小写不敏感。
function matchKeyword(product, keyword) {
  const key = String(keyword || '').trim().toLowerCase();
  if (!key) return true;
  const name = String(product.name || '').toLowerCase();
  const id = String(product.id || '').toLowerCase();
  return name.indexOf(key) >= 0 || id.indexOf(key) >= 0;
}

Page(
  withShare({
    data: {
      ready: false,
      title: '门店选品',
      storeName: '',
      keyword: '',
      catTabs: [{ id: 'all', label: '全部', count: 0 }],
      activeCatId: 'all',
      statusTabs: STATUS_TABS.map(item => Object.assign({}, item, { count: 0 })),
      activeStatusId: 'all',
      hasFilter: false,
      products: [],
      // 分页状态：current 从 1 开始；hasMore 控制触底是否继续加载
      current: 1,
      size: 20,
      total: 0,
      hasMore: false,
      loading: false,
      loadingMore: false,
      loadError: '',
      stats: { total: 0, listed: 0, unlisted: 0 },
      selectedCount: 0,
      selectedListedCount: 0,
      selectedUnlistedCount: 0
    },
    onLoad() {
      this.syncRole();
    },
    onShow() {
      // 从详情页返回：保留勾选并重新拉第 1 页（后台可能已改价/上下架）
      if (this.data.ready) this.reload({ keepSelection: true });
    },
    /** 触底加载下一页。 */
    onReachBottom() {
      this.loadMore();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false, title: '门店选品' });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const boundStore = getBoundStore(role.id);
      if (!boundStore) {
        this.setData({ ready: false, title: '门店选品' });
        wx.showToast({ title: '仅门店角色可使用选品管理', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      // 选品按经营门店隔离：subjectId 为后端数字主键，读写接口都以它为准。
      const subjectId = getCurrentSubjectId();
      if (subjectId == null) {
        this.setData({ ready: false, title: '门店选品' });
        wx.showToast({ title: '未获取到门店信息，请重新选择角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.storeSubjectId = subjectId;
      const title = boundStore.name ? `${boundStore.name}选品` : '门店选品';
      this.setData({ ready: true, title, storeName: boundStore.name }, () => this.reload());
    },
    /**
     * 从第 1 页重新加载（首次进入 / 下拉刷新 / 筛选变更）。
     *
     * keepSelection 用于从详情页返回时保留勾选（仅同一次会话内有效，
     * 跨筛选变更会清空选择以避免误批量操作）。
     */
    reload(options = {}) {
      const keepSelection = Boolean(options.keepSelection);
      const selected = keepSelection ? this.selectedIds() : [];
      this.setData({ current: 1, loadError: '', loading: true, products: [] }, () =>
        this.fetchPage({ append: false, selected })
      );
    },
    /**
     * 拉取一页商品。
     *
     * 筛选（关键词 / 分类 / 状态）全部由后端下推，total 才准确；
     * append=true 表示触底加载下一页，与首屏数据合并。
     */
    fetchPage(options = {}) {
      const append = Boolean(options.append);
      const selected = options.selected || this.selectedIds();
      const params = {
        current: this.data.current,
        size: this.data.size
      };
      if (this.data.keyword) params.keyword = this.data.keyword;
      // 分类 tab 的 id 优先用后端分类主键；'all' 表示不筛选
      if (this.data.activeCatId && this.data.activeCatId !== 'all') params.categoryId = this.data.activeCatId;
      if (this.data.activeStatusId === 'listed') params.listed = true;
      if (this.data.activeStatusId === 'unlisted') params.listed = false;

      return api
        .fetchStoreProducts(this.storeSubjectId, params)
        .then(page => {
          const records = (page && page.records) || [];
          const total = Number(page && page.total) || 0;
          const mapped = records.map(item =>
            Object.assign({}, item, {
              selected: selected.indexOf(item.id) >= 0,
              priceText: this.formatPrice(item.price)
            })
          );
          const products = append ? this.data.products.concat(mapped) : mapped;
          const current = Number(page && page.current) || this.data.current;
          this.setData({
            products,
            current,
            total,
            hasMore: products.length < total,
            loading: false,
            loadingMore: false,
            loadError: ''
          });
          this.refreshStats(products, total);
          this.rebuildTabs(products);
          this.syncSelectionSummary();
        })
        .catch(() => {
          this.setData({
            loading: false,
            loadingMore: false,
            loadError: '商品加载失败，请下拉重试'
          });
          if (!append) this.refreshStats([], 0);
        });
    },
    /** 触底加载下一页。 */
    loadMore() {
      if (!this.data.hasMore || this.data.loading || this.data.loadingMore) return Promise.resolve();
      this.setData({ loadingMore: true, current: this.data.current + 1 }, () =>
        this.fetchPage({ append: true })
      );
    },
    /** 价格（分 -> 元，保留 2 位）。 */
    formatPrice(fen) {
      const value = (Number(fen) || 0) / 100;
      return value.toFixed(2);
    },
    /**
     * 统计卡：全部 / 已上架 / 已下架。
     *
     * 注意：分页下前端只有当前页数据，故「全部」以 total 为准；
     * 已上架 / 已下架由后端按状态分别计数（见 refreshStats 的两次轻量查询）。
     */
    refreshStats(products, total) {
      // 当前页能确定的部分先展示，随后用后端计数校准（见 refreshStatsFromRemote）。
      const listed = products.filter(item => item.listed).length;
      const unlisted = products.length - listed;
      this.setData({
        stats: { total, listed, unlisted }
      });
      this.refreshStatsFromRemote(total);
    },
    /** 用后端计数校准「已上架 / 已下架」个数（当前页数据不足以统计全量）。 */
    refreshStatsFromRemote(total) {
      if (this.storeSubjectId == null) return;
      const base = { current: 1, size: 1 };
      Promise.all([
        api.fetchStoreProducts(this.storeSubjectId, Object.assign({}, base, { listed: true })),
        api.fetchStoreProducts(this.storeSubjectId, Object.assign({}, base, { listed: false }))
      ])
        .then(([listedPage, unlistedPage]) => {
          this.setData({
            stats: {
              total: Number(total) || 0,
              listed: Number(listedPage && listedPage.total) || 0,
              unlisted: Number(unlistedPage && unlistedPage.total) || 0
            }
          });
        })
        .catch(() => {
          // 计数失败保留当前页统计，不影响主流程
        });
    },
    /**
     * 从当前页数据推导分类 tab。
     *
     * 说明：分页下当前页不含全部分类时，tab 会随翻页补齐；
     * 这是避免再开一个「分类聚合」接口的取舍，已够用（分类数量少且稳定）。
     */
    rebuildTabs(products) {
      const seen = [];
      (products || []).forEach(item => {
        const key = item.categoryId == null ? item.categoryLabel : item.categoryId;
        if (key && !seen.some(entry => entry.key === key)) {
          seen.push({ key, id: item.categoryId == null ? item.categoryLabel : item.categoryId, label: item.categoryLabel });
        }
      });
      const catTabs = [{ id: 'all', label: '全部', count: this.data.total }].concat(
        seen.map(entry => ({
          id: entry.id,
          label: entry.label,
          count: (products || []).filter(p => (p.categoryId == null ? p.categoryLabel : p.categoryId) === entry.key).length
        }))
      );
      const statusTabs = STATUS_TABS.map(item => {
        let count = this.data.total;
        if (item.id === 'listed') count = this.data.stats.listed;
        if (item.id === 'unlisted') count = this.data.stats.unlisted;
        return { id: item.id, label: item.label, count };
      });
      this.setData({ catTabs, statusTabs, hasFilter: Boolean(this.data.keyword || this.data.activeCatId !== 'all' || this.data.activeStatusId !== 'all') });
    },
    selectedIds() {
      return (this.data.products || []).filter(item => item.selected).map(item => item.id);
    },
    /** 选择汇总（底部批量条）。 */
    syncSelectionSummary() {
      const selectedItems = (this.data.products || []).filter(item => item.selected);
      this.setData({
        selectedCount: selectedItems.length,
        selectedListedCount: selectedItems.filter(item => item.listed).length,
        selectedUnlistedCount: selectedItems.filter(item => !item.listed).length
      });
    },

    // 筛选变化后回到第 1 页重新拉取（筛选下推后端，保证 total 准确）。
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.reload());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.reload());
    },
    switchCat(event) {
      this.setData({ activeCatId: event.currentTarget.dataset.id }, () => this.reload());
    },
    switchStatus(event) {
      this.setData({ activeStatusId: event.currentTarget.dataset.id }, () => this.reload());
    },
    // 点击卡片主体进入商品详情；勾选与开关已用 catchtap 阻止冒泡。
    openDetail(event) {
      const id = event.currentTarget.dataset.id;
      if (!id) return;
      wx.navigateTo({ url: `${DETAIL_URL}?id=${id}` });
    },
    toggleSelect(event) {
      const id = event.currentTarget.dataset.id;
      const products = this.data.products.map(item =>
        item.id === id ? Object.assign({}, item, { selected: !item.selected }) : item
      );
      this.setData({ products });
      this.syncSelectionSummary();
    },
    clearSelection() {
      const products = this.data.products.map(item => Object.assign({}, item, { selected: false }));
      this.setData({ products });
      this.syncSelectionSummary();
    },
    // 单个商品直接切换上下架（服务端写操作，成功后局部回写，避免整页重载丢失滚动位置）。
    toggleListing(event) {
      const { id } = event.currentTarget.dataset;
      const target = this.data.products.find(item => item.id === id);
      if (!target || target.productId == null) return;
      const next = !target.listed;
      if (this.listing) return;
      this.listing = true;
      api
        .updateStoreListing(this.storeSubjectId, target.productId, next)
        .then(() => {
          this.listing = false;
          this.patchProduct(id, { listed: next });
          wx.showToast({ title: next ? '已上架' : '已下架', icon: 'none' });
        })
        .catch(error => {
          this.listing = false;
          wx.showToast({ title: (error && error.message) || '操作失败，请重试', icon: 'none' });
        });
    },
    patchProduct(id, patch) {
      const products = this.data.products.map(item =>
        item.id === id ? Object.assign({}, item, patch) : item
      );
      this.setData({ products });
      this.syncSelectionSummary();
      // 校准统计与 tab 计数
      this.refreshStats(products, this.data.total);
      this.rebuildTabs(products);
    },
    // 批量操作走二次确认，避免误触导致门店整体下架。
    batchUpdate(event) {
      const listed = event.currentTarget.dataset.listed === 'true' || event.currentTarget.dataset.listed === true;
      const ids = this.selectedIds();
      if (!ids.length) {
        wx.showToast({ title: '请先选择商品', icon: 'none' });
        return;
      }
      // 批量接口按商品主键提交（列表项 id 是业务编号）
      const productIds = this.data.products
        .filter(item => item.selected && item.productId != null)
        .map(item => item.productId);
      if (!productIds.length) {
        wx.showToast({ title: '请先选择商品', icon: 'none' });
        return;
      }
      const action = listed ? '上架' : '下架';
      wx.showModal({
        title: `批量${action}`,
        content: `确定将已选的 ${productIds.length} 个商品${action}吗？`,
        confirmText: '确定',
        cancelText: '取消',
        success: res => {
          if (!res.confirm) return;
          wx.showLoading({ title: `${action}中`, mask: true });
          api
            .updateStoreListingBatch(this.storeSubjectId, productIds, listed)
            .then(() => {
              wx.hideLoading();
              wx.showToast({ title: `已${action} ${productIds.length} 个商品`, icon: 'none' });
              // 服务端为准：重新拉取当前筛选下的第 1 页
              this.reload();
            })
            .catch(error => {
              wx.hideLoading();
              wx.showToast({ title: (error && error.message) || '操作失败，请重试', icon: 'none' });
            });
        }
      });
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
