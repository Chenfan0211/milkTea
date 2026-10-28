const api = require('../../../utils/api');
const mall = require('../../utils/mall');

const PAGE_SIZE = 10;

Page({
  data: {
    categories: [{ id: 0, name: '全部', iconImage: mall.categoryIconUrl('') }],
    activeCategoryId: 0,
    products: [],
    page: 1,
    total: 0,
    hasMore: true,
    loading: true,
    loadingMore: false,
    error: ''
  },

  onLoad() {
    this.loadCategories();
    this.loadProducts(true);
  },

  loadCategories() {
    api
      .fetchMallCategories()
      .then(categories => {
        const list = (Array.isArray(categories) ? categories : []).map(mall.normalizeCategory);
        this.setData({ categories: [{ id: 0, name: '全部', iconImage: mall.categoryIconUrl('') }].concat(list) });
      })
      .catch(() => {
        // 分类失败不阻断商品浏览，保留「全部」。
      });
  },

  loadProducts(reset) {
    if (reset) {
      this.setData({ loading: true, error: '', page: 1, hasMore: true });
    } else if (this.data.loadingMore || !this.data.hasMore) {
      return;
    }

    const page = reset ? 1 : this.data.page + 1;
    const categoryId = Number(this.data.activeCategoryId) || undefined;
    const requestId = Date.now();
    this.productRequestId = requestId;
    if (!reset) this.setData({ loadingMore: true });

    api
      .fetchMallProducts({ page, size: PAGE_SIZE, categoryId })
      .then(result => {
        if (this.productRequestId !== requestId) return;
        const records = ((result && result.records) || []).map(mall.normalizeProduct);
        const products = reset ? records : this.data.products.concat(records);
        const total = Number(result && result.total) || products.length;
        this.setData({
          products,
          page,
          total,
          loading: false,
          loadingMore: false,
          error: '',
          hasMore: products.length < total && records.length > 0
        });
      })
      .catch(error => {
        if (this.productRequestId !== requestId) return;
        this.setData({
          loading: false,
          loadingMore: false,
          error: (error && error.message) || '商品加载失败，请稍后重试'
        });
      });
  },

  selectCategory(event) {
    const id = Number(event.currentTarget.dataset.id) || 0;
    if (id === this.data.activeCategoryId) return;
    this.setData({ activeCategoryId: id, products: [] });
    this.loadProducts(true);
  },

  loadMore() {
    this.loadProducts(false);
  },

  retry() {
    this.loadProducts(true);
  },

  openProduct(event) {
    const id = Number(event.currentTarget.dataset.id);
    if (!id) return;
    wx.navigateTo({ url: `/packageMall/pages/product-detail/product-detail?id=${id}` });
  },

  openCart() {
    wx.navigateTo({ url: '/packageMall/pages/cart/cart' });
  },

  openOrders() {
    wx.navigateTo({ url: '/packageMall/pages/orders/orders' });
  }
});
