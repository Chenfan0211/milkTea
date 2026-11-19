const api = require('../../../utils/api');
const mall = require('../../utils/mall');
const mallCart = require('../../../utils/mall-cart');
const mallBanner = require('../../../utils/mall-banner');
const { withShare } = require('../../../utils/share');

const PAGE_SIZE = 10;

function cartQuantity(items) {
  return (Array.isArray(items) ? items : []).reduce((sum, item) => sum + (Number(item.quantity) || 0), 0);
}

Page(
  withShare(
    {
      data: {
        mallBanners: [],
        categories: [{ id: 0, name: '全部', iconImage: mall.categoryIconUrl('') }],
        cartCount: 0,
        cartCountText: '',
        visibleGalleryIds: {},
        cardGalleryIndexes: {},
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
        this.loadBanners();
        this.loadCategories();
        this.loadProducts(true);
        this.hasShown = false;
      },

      onShow() {
        this.syncCartCount();
        if (this.hasShown) this.loadProducts(true);
        this.hasShown = true;
        this.scheduleProductCardObserver();
      },

      onHide() {
        this.disconnectProductCardObserver();
        this.setData({ visibleGalleryIds: {} });
      },

      onUnload() {
        this.disconnectProductCardObserver();
      },

      syncCartCount() {
        const count = cartQuantity(mallCart.read());
        this.setData({
          cartCount: count,
          cartCountText: count > 99 ? '99+' : String(count)
        });
      },

      loadCategories() {
        api
          .fetchMallCategories()
          .then(categories => {
            const list = (Array.isArray(categories) ? categories : []).map(mall.normalizeCategory);
            this.setData({
              categories: [{ id: 0, name: '全部', iconImage: mall.categoryIconUrl('') }].concat(list)
            });
          })
          .catch(() => {
            // 分类失败不阻断商品浏览，保留「全部」。
          });
      },

      loadBanners() {
        api
          .fetchMallConfig()
          .then(config => {
            this.setData({
              mallBanners: mallBanner.normalizeBanners(config, api.resolveAssetUrl)
            });
          })
          .catch(() => {
            // 配置失败不阻断商城浏览，保持轮播隐藏。
          });
      },

      handleMallBannerError(event) {
        const index = Number(
          event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.index
        );
        this.setData({
          mallBanners: mallBanner.removeFailedBanner(this.data.mallBanners, Number.isFinite(index) ? index : 0)
        });
      },

      openMallBanner(event) {
        const index = Number(
          event && event.currentTarget && event.currentTarget.dataset && event.currentTarget.dataset.index
        );
        const item = this.data.mallBanners[index];
        if (!item) return null;
        return mallBanner.openBannerTarget(item, {
          fetchMallProductDetail: api.fetchMallProductDetail,
          navigateTo: url => wx.navigateTo({ url }),
          showToast: options => wx.showToast(options)
        });
      },

      handleCategoryIconError(event) {
        const id = Number(event.currentTarget.dataset.id);
        const categories = (this.data.categories || []).map(item => {
          if (Number(item.id) !== id || item.iconFallbackApplied) return item;
          return Object.assign({}, item, {
            iconImage: mall.CATEGORY_PLACEHOLDER_IMAGE,
            iconFallbackApplied: true
          });
        });
        this.setData({ categories });
      },

      loadProducts(reset) {
        if (reset) {
          this.disconnectProductCardObserver();
          this.setData({
            loading: true,
            error: '',
            page: 1,
            hasMore: true,
            visibleGalleryIds: {},
            cardGalleryIndexes: {}
          });
        } else if (this.data.loadingMore || !this.data.hasMore) {
          return;
        }

        const page = reset ? 1 : this.data.page + 1;
        const categoryId = Number(this.data.activeCategoryId) || undefined;
        const requestId = Date.now();
        this.productRequestId = requestId;
        if (!reset) this.setData({ loadingMore: true });

        Promise.all([api.fetchMallProducts({ page, size: PAGE_SIZE, categoryId }), mall.fetchLeaderBuyer()])
          .then(([result, leaderBuyer]) => {
            if (this.productRequestId !== requestId) return;
            const records = ((result && result.records) || []).map(row => mall.normalizeProduct(row, leaderBuyer));
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
            this.scheduleProductCardObserver();
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
        this.disconnectProductCardObserver();
        this.setData({ activeCategoryId: id, products: [] });
        this.loadProducts(true);
      },

      handleCardGalleryChange(event) {
        const id = Number(event && event.currentTarget && event.currentTarget.dataset.id);
        const index = Number(event && event.detail && event.detail.current);
        if (!id || !Number.isFinite(index)) return;
        this.setData({
          cardGalleryIndexes: Object.assign({}, this.data.cardGalleryIndexes, { [id]: index })
        });
      },

      scheduleProductCardObserver() {
        this.disconnectProductCardObserver();
        if (typeof wx.nextTick === 'function') {
          wx.nextTick(() => this.setupProductCardObserver());
        } else {
          this.setupProductCardObserver();
        }
      },

      setupProductCardObserver() {
        if (!this.data.products.length || typeof wx.createIntersectionObserver !== 'function') return;
        const observer = wx.createIntersectionObserver(this, { observeAll: true });
        this.productCardObserver = observer;
        observer.relativeToViewport({ top: 0, bottom: 0 }).observe('.product-card__image-wrap', entry => {
          const id = Number(entry && entry.dataset && entry.dataset.id);
          if (!id) return;
          const visible = Number(entry.intersectionRatio) > 0;
          this.setData({
            visibleGalleryIds: Object.assign({}, this.data.visibleGalleryIds, { [id]: visible })
          });
        });
      },

      disconnectProductCardObserver() {
        if (this.productCardObserver) {
          this.productCardObserver.disconnect();
          this.productCardObserver = null;
        }
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
    },
    { mall: true }
  )
);
