const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { buildProductDetailViewModel } = require('../../utils/product-detail-model');
const { buildSpecState, normalizeSpecProduct } = require('../../utils/spec-sheet');
const { buildCartItem } = require('../../utils/cart');
const cartState = require('../../utils/recommendation-cart-state');

const STORE_PAGE = '/pages/recommendation-stores/recommendation-stores';

Page(
  withShare({
    data: {
      loading: true,
      error: '',
      missing: false,
      product: {},
      selection: buildSpecState({}, { priceMode: 'sale' })
    },

    onLoad(options) {
      this.productId = String((options && options.productId) || '').trim();
      this.reportId = String((options && options.reportId) || '').trim();
      if (!this.productId) {
        this.setData({ loading: false, missing: true, error: '', product: {} });
        return;
      }
      this.load();
    },

    load() {
      if (!this.productId) return;
      this.setData({ loading: true, error: '', missing: false });
      api
        .fetchProductDetail(this.productId)
        .then(detail => {
          if (!detail || !detail.id) {
            this.setData({ loading: false, error: '', missing: true, product: {} });
            return;
          }
          const normalized = normalizeSpecProduct(detail);
          this.product = normalized;
          this.setData({
            loading: false,
            error: '',
            missing: false,
            product: buildProductDetailViewModel(normalized),
            selection: buildSpecState(normalized, { priceMode: 'sale' })
          });
        })
        .catch(error => {
          this.setData({
            loading: false,
            missing: false,
            error: (error && error.message) || '商品详情加载失败'
          });
        });
    },

    selectSpecOption(event) {
      const dataset = (event && event.currentTarget && event.currentTarget.dataset) || {};
      const groupId = String(dataset.groupId || '');
      const optionId = String(dataset.optionId || '');
      if (!groupId || !optionId) return;
      const selectedOptionIds = (this.data.selection.selectedOptions || [])
        .filter(option => option.groupId !== groupId)
        .map(option => option.id);
      selectedOptionIds.push(optionId);
      this.setData({
        selection: buildSpecState(this.product || {}, {
          initialSelectedOptionIds: selectedOptionIds,
          initialQuantity: this.data.selection.quantity,
          priceMode: 'sale'
        })
      });
    },

    increaseQuantity() {
      this.setData({
        selection: Object.assign({}, this.data.selection, { quantity: this.data.selection.quantity + 1 })
      });
    },

    decreaseQuantity() {
      if (this.data.selection.quantity <= 1) return;
      this.setData({
        selection: Object.assign({}, this.data.selection, { quantity: this.data.selection.quantity - 1 })
      });
    },

    buildOrderPayload() {
      const selection = this.data.selection;
      return {
        product: this.product || {},
        selectedOptions: selection.selectedOptions || [],
        quantity: selection.quantity,
        unitPrice: selection.memberPrice,
        originalPrice: selection.originalPrice,
        storedValuePrice: selection.storedValuePrice,
        storedValueDiscount: selection.storedValueDiscount,
        specText: selection.specText,
        priceMode: selection.priceMode || 'sale'
      };
    },

    handleAddCart() {
      if (!this.reportId) {
        wx.showToast({ title: '请从体质报告进入后加购', icon: 'none' });
        return;
      }
      const payload = this.buildOrderPayload();
      if (!payload.product || !payload.product.id) {
        wx.showToast({ title: '商品信息缺失', icon: 'none' });
        return;
      }
      const item = buildCartItem({
        product: payload.product,
        selectedOptions: payload.selectedOptions,
        quantity: payload.quantity,
        unitPrice: payload.unitPrice,
        originalPrice: payload.originalPrice,
        storedValuePrice: payload.storedValuePrice,
        storedValueDiscount: payload.storedValueDiscount,
        specText: payload.specText,
        priceMode: payload.priceMode || 'sale'
      });
      cartState.mergeItems(this.reportId, [item]);
      wx.showToast({ title: '已加入购物车', icon: 'none' });
      wx.navigateBack({ delta: 1 });
    },

    buildPendingItem(payload, cartItemId) {
      const product = (payload && payload.product) || {};
      const selectedOptionIds = ((payload && payload.selectedOptions) || []).map(option => option.id);
      return {
        productId: product.id,
        name: product.name,
        image: product.image,
        selectedOptionIds,
        quantity: (payload && payload.quantity) || 1,
        priceMode: (payload && payload.priceMode) || 'sale',
        cartItemId: cartItemId || ''
      };
    },

    openStoreSelection(intent, items, cartScope) {
      if (!items.length) {
        wx.showToast({ title: '暂无可结算商品', icon: 'none' });
        return;
      }
      getApp().globalData.pendingRecommendationAdd = {
        reportId: this.reportId || '',
        intent,
        cartScope: cartScope || '',
        items
      };
      wx.navigateTo({ url: STORE_PAGE });
    },

    handleBuyNow() {
      this.openStoreSelection('buyNow', [this.buildPendingItem(this.buildOrderPayload(), '')], '');
    }
  })
);
