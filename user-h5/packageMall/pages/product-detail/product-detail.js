const api = require('../../../utils/api');
const loginGuard = require('../../../utils/login-guard');
const mallCart = require('../../../utils/mall-cart');
const mall = require('../../utils/mall');

Page({
  data: {
    productId: 0,
    loading: true,
    error: '',
    product: {},
    selectedSkuId: 0,
    selectedSku: null,
    quantity: 1,
    galleryImages: [],
    canBuy: false,
    priceText: '¥0'
  },

  onLoad(options) {
    const productId = Number(options && options.id);
    this.setData({ productId });
    if (!productId) {
      this.setData({ loading: false, error: '商品编号缺失' });
      return;
    }
    this.loadProduct();
  },

  loadProduct() {
    this.setData({ loading: true, error: '' });
    api
      .fetchMallProductDetail(this.data.productId)
      .then(detail => {
        const product = mall.normalizeDetail(detail);
        const selectedSku = product.skus.find(sku => !sku.soldOut) || product.skus[0] || null;
        this.setData({
          loading: false,
          error: '',
          product,
          selectedSkuId: selectedSku ? selectedSku.id : 0,
          selectedSku,
          quantity: 1,
          galleryImages: product.galleryImages.length ? product.galleryImages : [product.image],
          priceText: selectedSku ? selectedSku.salePriceText : product.priceText,
          canBuy: Boolean(selectedSku && !selectedSku.soldOut)
        });
      })
      .catch(error => {
        this.setData({
          loading: false,
          error: (error && error.message) || '商品详情加载失败'
        });
      });
  },

  selectSku(event) {
    const skuId = Number(event.currentTarget.dataset.id);
    const selectedSku = (this.data.product.skus || []).find(sku => sku.id === skuId);
    if (!selectedSku) return;
    this.setData({
      selectedSkuId: skuId,
      selectedSku,
      quantity: 1,
      priceText: selectedSku.salePriceText,
      canBuy: !selectedSku.soldOut
    });
  },

  increaseQuantity() {
    const sku = this.data.selectedSku;
    if (!sku || sku.soldOut) return;
    const max = Math.min(99, sku.availableStock);
    if (this.data.quantity >= max) {
      wx.showToast({ title: '已达到可购买库存', icon: 'none' });
      return;
    }
    this.setData({ quantity: this.data.quantity + 1 });
  },

  decreaseQuantity() {
    if (this.data.quantity <= 1) return;
    this.setData({ quantity: this.data.quantity - 1 });
  },

  addToCart() {
    if (!this.data.canBuy) {
      wx.showToast({ title: '该规格已售罄', icon: 'none' });
      return;
    }
    loginGuard.requireLogin(
      () => {
        this.validateAndUseSku(item => {
          mallCart.add(item);
          wx.showToast({ title: '已加入购物车', icon: 'none' });
        });
      },
      { reason: '登录后可使用购物车' }
    );
  },

  buyNow() {
    if (!this.data.canBuy) {
      wx.showToast({ title: '该规格已售罄', icon: 'none' });
      return;
    }
    loginGuard.requireLogin(
      () => {
        this.validateAndUseSku(item => {
          const query = `source=buy&productId=${item.productId}&skuId=${item.skuId}&quantity=${item.quantity}`;
          wx.navigateTo({ url: `/packageMall/pages/checkout/checkout?${query}` });
        });
      },
      { reason: '登录后可以购买商品' }
    );
  },

  validateAndUseSku(onSuccess) {
    const product = this.data.product;
    const sku = this.data.selectedSku;
    if (!product.id || !sku) return;
    const item = {
      skuId: sku.id,
      productId: product.id,
      name: product.name,
      specValue: sku.specValue,
      image: sku.image || product.image,
      salePriceFen: sku.salePrice,
      originalPriceFen: sku.originalPrice,
      availableStock: sku.availableStock,
      quantity: this.data.quantity,
      selected: true,
      listed: true
    };
    wx.showLoading({ title: '校验库存', mask: true });
    mall
      .validateItems([item])
      .then(items => {
        wx.hideLoading();
        const checked = items[0];
        if (!checked || !checked.valid) {
          wx.showToast({ title: (checked && checked.reason) || '商品暂不可购买', icon: 'none' });
          return;
        }
        onSuccess(checked);
      })
      .catch(error => {
        wx.hideLoading();
        wx.showToast({ title: (error && error.message) || '库存校验失败', icon: 'none' });
      });
  },

  openCart() {
    wx.navigateTo({ url: '/packageMall/pages/cart/cart' });
  },

  openOrders() {
    wx.navigateTo({ url: '/packageMall/pages/orders/orders' });
  },

  retry() {
    this.loadProduct();
  }
});
