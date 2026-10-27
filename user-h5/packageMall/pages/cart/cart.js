const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mallCart = require('../../../utils/mall-cart');
const mall = require('../../utils/mall');

Page({
  data: {
    items: [],
    count: 0,
    amountText: '0',
    allSelected: false,
    checking: false,
    needLogin: false,
    error: ''
  },

  onShow() {
    this.loadCart();
  },

  loadCart() {
    if (!auth.isLoggedIn()) {
      this.setData({ needLogin: true, items: [], count: 0, amountText: '0', allSelected: false });
      return;
    }
    const rawItems = mallCart.read();
    this.setData({ needLogin: false, error: '' });
    if (!rawItems.length) {
      this.applyItems([]);
      return;
    }
    this.refreshValidation(rawItems);
  },

  // 进入购物车和结算前都按实际数量调用 validateMallSkus，刷新价格与可用库存。
  refreshValidation(sourceItems) {
    const items = Array.isArray(sourceItems) ? sourceItems : this.data.items;
    if (!items.length) {
      this.applyItems([]);
      return Promise.resolve([]);
    }
    this.setData({ checking: true, error: '' });
    return mall
      .validateItems(items)
      .then(validated => {
        const invalid = validated.find(item => !item.valid);
        if (invalid) {
          throw new Error(invalid.reason || '部分商品不可购买');
        }
        return mall.quoteItems(validated).then(quote => {
          if (quote && quote.orderable === false) {
            throw new Error(quote.orderError || '当前商品无法合并下单');
          }
          const quoteMap = ((quote && quote.items) || []).reduce((map, row) => {
            map[String(row.skuId)] = row;
            return map;
          }, {});
          const merged = validated.map(item => {
            const quoted = quoteMap[String(item.skuId)] || {};
            const unitPrice = Number(quoted.unitPrice) || Number(item.salePriceFen) || 0;
            return Object.assign({}, item, {
              unitPrice,
              salePriceFen: unitPrice,
              lineAmount: Number(quoted.lineAmount) || unitPrice * item.quantity,
              valid: quoted.valid !== false && item.valid !== false,
              reason: quoted.reason || item.reason || '',
              priceChanged: Boolean(item.salePriceFen && item.salePriceFen !== unitPrice)
            });
          });
          const invalidQuote = merged.find(item => !item.valid);
          if (invalidQuote) {
            throw new Error(invalidQuote.reason || '商品价格或库存已变化');
          }
          mallCart.write(merged);
          this.applyItems(merged);
          this.setData({
            checking: false,
            error: merged.some(item => item.priceChanged) ? '部分商品价格已更新，请确认后结算' : ''
          });
          return merged;
        });
      })
      .catch(error => {
        this.applyItems(items);
        this.setData({
          checking: false,
          error: (error && error.message) || '购物车校验失败'
        });
        throw error;
      });
  },

  applyItems(items) {
    const decorated = (Array.isArray(items) ? items : []).map(item => {
      const stock = Math.max(0, Number(item.availableStock) || 0);
      const unavailable = !item.listed || stock <= 0;
      return Object.assign({}, item, {
        priceText: mall.priceText(item.salePriceFen),
        unitPriceText: mall.priceText(item.salePriceFen),
        unavailable,
        stockInsufficient: !unavailable && Number(item.quantity) > stock,
        statusText: !item.listed ? '已失效' : stock <= 0 ? '已售罄' : Number(item.quantity) > stock ? '库存不足' : ''
      });
    });
    const summary = mallCart.summary(decorated);
    const selectable = decorated.filter(item => !item.unavailable && item.quantity <= item.availableStock);
    this.setData({
      items: decorated,
      count: summary.count,
      amountText: mall.moneyText(summary.amountFen),
      allSelected: selectable.length > 0 && selectable.every(item => item.selected)
    });
  },

  toggleSelected(event) {
    const skuId = Number(event.currentTarget.dataset.id);
    const item = this.data.items.find(entry => Number(entry.skuId) === skuId);
    if (!item) return;
    if (item.unavailable) {
      wx.showToast({ title: item.statusText || '商品不可购买', icon: 'none' });
      return;
    }
    if (item.quantity > item.availableStock) {
      wx.showToast({ title: '请先减少购买数量', icon: 'none' });
      return;
    }
    this.updateItem(skuId, { selected: !item.selected });
  },

  toggleAll() {
    const selectAll = !this.data.allSelected;
    const next = this.data.items.map(item => {
      if (item.unavailable || item.quantity > item.availableStock) return item;
      return Object.assign({}, item, { selected: selectAll });
    });
    mallCart.write(next);
    this.applyItems(next);
  },

  increaseQuantity(event) {
    const skuId = Number(event.currentTarget.dataset.id);
    const item = this.data.items.find(entry => Number(entry.skuId) === skuId);
    if (!item || item.unavailable) return;
    const max = Math.min(99, item.availableStock);
    if (item.quantity >= max) {
      wx.showToast({ title: '已达到可购买库存', icon: 'none' });
      return;
    }
    this.updateItem(skuId, { quantity: item.quantity + 1, selected: false });
  },

  decreaseQuantity(event) {
    const skuId = Number(event.currentTarget.dataset.id);
    const item = this.data.items.find(entry => Number(entry.skuId) === skuId);
    if (!item || item.quantity <= 1) return;
    const quantity = item.quantity - 1;
    this.updateItem(skuId, {
      quantity,
      selected: item.listed && item.availableStock > 0 && quantity <= item.availableStock
    });
  },

  updateItem(skuId, patch) {
    const next = this.data.items.map(item =>
      Number(item.skuId) === Number(skuId) ? Object.assign({}, item, patch) : item
    );
    mallCart.write(next);
    this.applyItems(next);
  },

  removeItem(event) {
    const skuId = Number(event.currentTarget.dataset.id);
    if (!skuId) return;
    wx.showModal({
      title: '删除商品',
      content: '确定从购物车删除该商品吗？',
      success: ({ confirm }) => {
        if (!confirm) return;
        const next = mallCart.remove(skuId);
        this.applyItems(next);
        wx.showToast({ title: '已删除', icon: 'none' });
      }
    });
  },

  openProduct(event) {
    const productId = Number(event.currentTarget.dataset.productId);
    if (!productId) return;
    wx.navigateTo({ url: `/packageMall/pages/product-detail/product-detail?id=${productId}` });
  },

  checkout() {
    if (this.data.checking) {
      wx.showToast({ title: '正在校验库存', icon: 'none' });
      return;
    }
    if (!this.data.count) {
      wx.showToast({ title: '请选择可购买商品', icon: 'none' });
      return;
    }
    this.refreshValidation(this.data.items)
      .then(items => {
        const selected = items.filter(item => item.selected && item.valid);
        if (!selected.length) {
          wx.showToast({ title: '所选商品暂不可购买', icon: 'none' });
          return;
        }
        wx.navigateTo({ url: '/packageMall/pages/checkout/checkout?source=cart' });
      })
      .catch(() => {
        // 错误状态已由 refreshValidation 呈现。
      });
  },

  retry() {
    this.loadCart();
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadCart(), { reason: '登录后可使用购物车' });
  },

  openOrders() {
    wx.navigateTo({ url: '/packageMall/pages/orders/orders' });
  }
});
