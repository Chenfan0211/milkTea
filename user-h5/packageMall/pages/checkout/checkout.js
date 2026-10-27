const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mallCart = require('../../../utils/mall-cart');
const { requestOrderPayment } = require('../../../utils/order-payment');
const { requestMallShippingSubscribe } = require('../../../utils/subscribe-message');
const mall = require('../../utils/mall');

Page({
  data: {
    source: 'cart',
    loading: true,
    needLogin: false,
    error: '',
    addresses: [],
    selectedAddress: null,
    items: [],
    count: 0,
    amountText: '0',
    paymentMethod: 'WXPAY',
    submitting: false
  },

  onLoad(options) {
    const opts = options || {};
    this.clientRequestId = 'mall-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
    this.checkoutOptions = {
      source: opts.source === 'buy' ? 'buy' : 'cart',
      productId: Number(opts.productId) || 0,
      skuId: Number(opts.skuId) || 0,
      quantity: Math.max(1, Number(opts.quantity) || 1),
      addressId: Number(opts.addressId) || 0
    };
    this.setData({ source: this.checkoutOptions.source });
    this.initialize();
  },

  onShow() {
    if (this.initialized && auth.isLoggedIn()) this.refreshAddresses();
  },

  initialize() {
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, needLogin: true, error: '' });
      return;
    }
    this.initialized = true;
    this.setData({ loading: true, needLogin: false, error: '' });
    Promise.all([this.loadCheckoutItems(), this.loadAddresses()])
      .then(() => this.setData({ loading: false }))
      .catch(error => {
        this.setData({
          loading: false,
          error: (error && error.message) || '结算信息加载失败'
        });
      });
  },

  loadCheckoutItems() {
    let rawItems;
    if (this.checkoutOptions.source === 'buy') {
      rawItems = [
        {
          skuId: this.checkoutOptions.skuId,
          productId: this.checkoutOptions.productId,
          quantity: this.checkoutOptions.quantity,
          selected: true,
          listed: true
        }
      ];
    } else {
      rawItems = mallCart.read().filter(item => item.selected);
    }
    if (!rawItems.length) {
      return Promise.reject(new Error('没有可结算的商品'));
    }
    return mall.validateItems(rawItems).then(items => {
      const invalid = items.find(item => !item.valid);
      if (invalid) {
        throw new Error(invalid.reason || '部分商品库存或状态已变化');
      }
      return mall.quoteItems(items).then(quote => {
        if (quote && quote.orderable === false) {
          throw new Error(quote.orderError || '当前商品无法合并下单');
        }
        const quoted = (quote && quote.items) || [];
        const invalidQuote = quoted.find(item => !item.valid);
        if (invalidQuote) {
          throw new Error(invalidQuote.reason || '商品价格或库存已变化');
        }
        this.checkoutQuote = quote;
        this.setData(this.applyCheckoutItems(items, quote));
      });
    });
  },

  applyCheckoutItems(items, quote) {
    const quoteMap = ((quote && quote.items) || []).reduce((map, row) => {
      map[String(row.skuId)] = row;
      return map;
    }, {});
    const displayItems = items.map(item => {
      const row = quoteMap[String(item.skuId)] || {};
      const unitPrice = Number(row.unitPrice) || 0;
      return Object.assign({}, item, {
        unitPrice,
        salePriceFen: unitPrice,
        lineAmount: Number(row.lineAmount) || unitPrice * item.quantity,
        unitPriceText: mall.priceText(unitPrice)
      });
    });
    return {
      items: displayItems,
      count: displayItems.reduce((sum, item) => sum + item.quantity, 0),
      amountText: mall.moneyText(Number(quote && quote.payableAmount) || 0)
    };
  },

  loadAddresses() {
    return api.fetchAddresses().then(result => {
      const addresses = (Array.isArray(result) ? result : []).map(mall.normalizeAddress);
      const preferredId = Number(
        (this.data.selectedAddress && this.data.selectedAddress.id) || this.checkoutOptions.addressId || 0
      );
      const selectedAddress =
        addresses.find(address => Number(address.id) === preferredId) ||
        addresses.find(address => address.isDefault) ||
        addresses[0] ||
        null;
      this.setData({ addresses, selectedAddress });
    });
  },

  refreshAddresses() {
    this.loadAddresses().catch(() => {
      // 返回确认页时地址刷新失败不覆盖已选地址。
    });
  },

  openAddressPicker() {
    const selectedId = this.data.selectedAddress ? this.data.selectedAddress.id : 0;
    wx.navigateTo({
      url: `/packageMall/pages/address/address?mode=select&selectedId=${selectedId}`,
      events: {
        selectAddress: address => this.setData({ selectedAddress: mall.normalizeAddress(address) })
      }
    });
  },

  addAddress() {
    wx.navigateTo({ url: '/packageMall/pages/address-edit/address-edit' });
  },

  selectPayment(event) {
    const method = event.currentTarget.dataset.method;
    if (method !== 'WXPAY' && method !== 'STORED_VALUE') return;
    this.setData({ paymentMethod: method });
  },

  validateCurrentItems() {
    return mall.validateItems(this.data.items).then(items => {
      const invalid = items.find(item => !item.valid);
      if (invalid) throw new Error(invalid.reason || '商品库存不足');
      this.setData(this.applyCheckoutItems(items));
      return items;
    });
  },

  submitOrder() {
    if (this.data.submitting) return;
    if (!this.data.selectedAddress) {
      wx.showToast({ title: '请选择收货地址', icon: 'none' });
      return;
    }
    if (!this.data.items.length) {
      wx.showToast({ title: '没有可结算的商品', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    const release = () => this.setData({ submitting: false });

    Promise.resolve()
      .then(() => requestMallShippingSubscribe())
      .catch(() => null)
      .then(() =>
        loginGuard.requirePhone(() => this.createAndPay(), {
          reason: '下单需要绑定手机号，用于订单通知与售后'
        })
      )
      .then(release, error => {
        release();
        wx.showToast({ title: (error && error.message) || '下单失败，请重试', icon: 'none' });
      });
  },

  createAndPay() {
    wx.showLoading({ title: '正在下单', mask: true });
    let createdOrder = null;
    return this.validateCurrentItems()
      .then(items => {
        const payChannel = this.data.paymentMethod === 'STORED_VALUE' ? 'STORED_VALUE' : 'WXPAY';
        return api.createMallOrder({
          clientRequestId: this.clientRequestId,
          addressId: this.data.selectedAddress.id,
          payChannel,
          items: items.map(item => ({ skuId: item.skuId, quantity: item.quantity }))
        });
      })
      .then(order => {
        createdOrder = order;
        const quotedAmount = Number(this.checkoutQuote && this.checkoutQuote.payableAmount) || 0;
        const serverAmount = Number(order.paidAmount || order.totalAmount) || 0;
        if (quotedAmount > 0 && quotedAmount !== serverAmount) {
          throw new Error('商品价格已更新，请刷新结算页后重新提交');
        }
        const amountFen = serverAmount;
        if (this.data.paymentMethod === 'STORED_VALUE') {
          return api.payOrderByBalance(order.orderNo).then(result => ({
            paid: mall.normalizeStatus(result && result.payStatus) === 'PAID',
            closed: mall.normalizeStatus(result && result.status) === 'CANCELED',
            order: result
          }));
        }
        return requestOrderPayment(order.orderNo, amountFen, { maxAttempts: 10 });
      })
      .then(result => {
        wx.hideLoading();
        this.finishPayment(createdOrder, result);
      })
      .catch(error => {
        wx.hideLoading();
        if (createdOrder && createdOrder.orderNo) {
          wx.showToast({ title: '订单已创建，请继续支付', icon: 'none' });
          setTimeout(() => this.redirectToOrder(createdOrder.orderNo), 500);
          return;
        }
        throw error;
      });
  },

  finishPayment(order, result) {
    if (result && result.paid) {
      if (this.data.source === 'cart') {
        mallCart.remove(this.data.items.map(item => item.skuId));
      }
      wx.showToast({ title: '支付成功', icon: 'success' });
    } else {
      wx.showToast({ title: '订单待支付', icon: 'none' });
    }
    setTimeout(() => this.redirectToOrder(order.orderNo), result && result.paid ? 400 : 600);
  },

  redirectToOrder(orderNo) {
    if (this.redirected || !orderNo) return;
    this.redirected = true;
    wx.redirectTo({ url: `/packageMall/pages/order-detail/order-detail?orderNo=${orderNo}` });
  },

  goLogin() {
    loginGuard.requireLogin(() => this.initialize(), { reason: '登录后可以结算' });
  },

  retry() {
    this.initialize();
  }
});
