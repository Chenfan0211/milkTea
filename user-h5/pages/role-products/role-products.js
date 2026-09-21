const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getBoundStore } = require('../../utils/roles');
const {
  getListingStats,
  getProductsForStore,
  resetListing,
  setListed,
  setListedBatch
} = require('../../utils/product-listing');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const DETAIL_URL = '/pages/role-product-detail/role-product-detail';
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
      filteredProducts: [],
      stats: { total: 0, listed: 0, unlisted: 0 },
      selectedCount: 0,
      selectedListedCount: 0,
      selectedUnlistedCount: 0
    },
    onLoad() {
      this.syncRole();
    },
    onShow() {
      if (this.data.ready) this.syncProducts({ keepSelection: true });
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
      // 选品按经营门店隔离，消费端在对应门店才看得到下架效果。
      this.storeId = boundStore.id;
      const title = boundStore.name ? `${boundStore.name}选品` : '门店选品';
      this.setData({ ready: true, title, storeName: boundStore.name }, () => this.syncProducts());
    },
    // 读取当前门店商品清单；keepSelection 用于从详情返回时保留勾选。
    syncProducts(options = {}) {
      const selected = options.keepSelection ? this.selectedIds() : [];
      const products = getProductsForStore(this.storeId).map(item =>
        Object.assign({}, item, { selected: selected.indexOf(item.id) >= 0 })
      );
      this.setData(
        { products, stats: getListingStats(this.storeId), catTabs: this.buildCatTabs(products) },
        () => this.applyFilter()
      );
    },
    // 分类 tab 与状态 tab 都在角标里展示当前匹配数量。
    buildCatTabs(products) {
      const seen = [];
      (products || []).forEach(item => {
        if (seen.indexOf(item.categoryLabel) === -1) seen.push(item.categoryLabel);
      });
      return [{ id: 'all', label: '全部', count: products.length }].concat(
        seen.map(label => ({
          id: label,
          label,
          count: products.filter(item => item.categoryLabel === label).length
        }))
      );
    },
    selectedIds() {
      return (this.data.products || []).filter(item => item.selected).map(item => item.id);
    },
    // 关键词 + 分类 + 状态三维同时生效（AND）；角标数量基于其余维度过滤后的集合。
    applyFilter() {
      const { keyword, activeCatId, activeStatusId } = this.data;
      const byKeyword = (this.data.products || []).filter(item => matchKeyword(item, keyword));
      const byCat =
        activeCatId === 'all' ? byKeyword : byKeyword.filter(item => item.categoryLabel === activeCatId);

      const catTabs = this.buildCatTabs(byKeyword);
      const statusTabs = STATUS_TABS.map(item => {
        let count = byCat.length;
        if (item.id === 'listed') count = byCat.filter(p => p.listed).length;
        if (item.id === 'unlisted') count = byCat.filter(p => !p.listed).length;
        return { id: item.id, label: item.label, count };
      });

      const filteredProducts =
        activeStatusId === 'all'
          ? byCat
          : byCat.filter(item => (activeStatusId === 'listed' ? item.listed : !item.listed));

      const selectedItems = (this.data.products || []).filter(item => item.selected);
      this.setData({
        catTabs,
        statusTabs,
        filteredProducts,
        hasFilter: Boolean(keyword || activeCatId !== 'all' || activeStatusId !== 'all'),
        selectedCount: selectedItems.length,
        selectedListedCount: selectedItems.filter(item => item.listed).length,
        selectedUnlistedCount: selectedItems.filter(item => !item.listed).length
      });
    },
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.applyFilter());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.applyFilter());
    },
    switchCat(event) {
      this.setData({ activeCatId: event.currentTarget.dataset.id }, () => this.applyFilter());
    },
    switchStatus(event) {
      this.setData({ activeStatusId: event.currentTarget.dataset.id }, () => this.applyFilter());
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
      this.setData({ products }, () => this.applyFilter());
    },
    clearSelection() {
      const products = this.data.products.map(item => Object.assign({}, item, { selected: false }));
      this.setData({ products }, () => this.applyFilter());
    },
    // 单个商品直接切换上下架。
    toggleListing(event) {
      const { id } = event.currentTarget.dataset;
      const target = this.data.products.find(item => item.id === id);
      if (!target) return;
      const next = !target.listed;
      setListed(this.storeId, id, next);
      this.patchProduct(id, { listed: next });
      wx.showToast({ title: next ? '已上架' : '已下架', icon: 'none' });
    },
    patchProduct(id, patch) {
      const products = this.data.products.map(item =>
        item.id === id ? Object.assign({}, item, patch) : item
      );
      this.setData({ products, stats: getListingStats(this.storeId) }, () => this.applyFilter());
    },
    // 批量操作走二次确认，避免误触导致门店整体下架。
    batchUpdate(event) {
      const listed = event.currentTarget.dataset.listed === 'true' || event.currentTarget.dataset.listed === true;
      const ids = this.selectedIds();
      if (!ids.length) {
        wx.showToast({ title: '请先选择商品', icon: 'none' });
        return;
      }
      const action = listed ? '上架' : '下架';
      wx.showModal({
        title: `批量${action}`,
        content: `确定将已选的 ${ids.length} 个商品${action}吗？`,
        confirmText: '确定',
        cancelText: '取消',
        success: res => {
          if (!res.confirm) return;
          setListedBatch(this.storeId, ids, listed);
          const products = this.data.products.map(item =>
            ids.indexOf(item.id) >= 0 ? Object.assign({}, item, { listed, selected: false }) : item
          );
          this.setData({ products, stats: getListingStats(this.storeId) }, () => this.applyFilter());
          wx.showToast({ title: `已${action} ${ids.length} 个商品`, icon: 'none' });
        }
      });
    },
    resetListing() {
      resetListing(this.storeId);
      this.syncProducts();
      wx.showToast({ title: '已恢复全部上架', icon: 'none' });
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