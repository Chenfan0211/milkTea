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
    amountText: '0.00',
    commissionText: '',
    hasCommission: false,
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
        this.assertQuoteOrderable(items, quote);
        this.checkoutQuote = quote;
        this.setData(this.applyCheckoutItems(items, quote));
      });
    });
  },

  assertQuoteOrderable(items, quote) {
    if (quote && quote.orderable === false) {
      throw new Error(quote.orderError || '当前商品无法合并下单');
    }
    const quoted = (quote && quote.items) || [];
    for (const item of items) {
      const row = quoted.find(entry => Number(entry.skuId) === Number(item.skuId));
      if (!row || !row.valid) throw new Error((row && row.reason) || '商品价格或库存已变化');
    }
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
    const commissionAmount = Number(quote && quote.commissionAmount) || 0;
    return {
      items: displayItems,
      count: displayItems.reduce((sum, item) => sum + item.quantity, 0),
      amountText: mall.moneyText(Number(quote && quote.payableAmount) || 0),
      // 非团长应付价含分销服务费，商品列表展示的是不含佣金的售价；
      // 这里显式展示差额，避免用户误以为结账时被加价。
      commissionText: commissionAmount > 0 ? mall.moneyText(commissionAmount) : '',
      hasCommission: commissionAmount > 0
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
    const previousQuote = this.checkoutQuote;
    const previousItems = this.data.items;
    return mall.validateItems(this.data.items).then(items => {
      const invalid = items.find(item => !item.valid);
      if (invalid) throw new Error(invalid.reason || '商品库存不足');
      return mall.quoteItems(items).then(quote => {
        this.assertQuoteOrderable(items, quote);
        const display = this.applyCheckoutItems(items, quote);
        const priceChanged =
          (previousQuote && Number(previousQuote.payableAmount) !== Number(quote.payableAmount)) ||
          display.items.some(item => {
            const previous = previousItems.find(row => Number(row.skuId) === Number(item.skuId));
            return previous && Number(previous.unitPrice) !== item.unitPrice;
          });
        this.checkoutQuote = quote;
        this.setData(display);
        if (priceChanged) throw new Error('商品价格已更新，请确认新金额后再次提交');
        return display.items;
      });
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
    const showFailure = error => {
      release();
      wx.showToast({ title: (error && error.message) || '下单失败，请重试', icon: 'none' });
    };
    // requirePhone 会把 action 的拒绝当作授权失败；业务失败须在 action 内结束。
    // 授权后续跑同样经过此处理，改价提示不会变成自动重试建单。
    const createAndPay = () => {
      this.setData({ submitting: true });
      return Promise.resolve()
        .then(() => this.createAndPay())
        .then(release, showFailure);
    };

    return Promise.resolve()
      .then(() => requestMallShippingSubscribe())
      .catch(() => null)
      .then(() =>
        loginGuard.requirePhone(createAndPay, {
          reason: '下单需要绑定手机号，用于订单通知与售后'
        })
      )
      .then(release, showFailure);
  },

  createAndPay() {
    const paymentUserId = auth.getUserId();
    const payChannel = this.data.paymentMethod === 'STORED_VALUE' ? 'STORED_VALUE' : 'WXPAY';
    wx.showLoading({ title: '正在下单', mask: true });
    let createdOrder = null;
    let settlementItems = [];
    return this.validateCurrentItems()
      .then(items => {
        if (paymentUserId !== auth.getUserId()) throw new Error('登录账号已变化，请重新结算');
        settlementItems = items;
        return api.createMallOrder({
          clientRequestId: this.clientRequestId,
          addressId: this.data.selectedAddress.id,
          payChannel,
          items: items.map(item => ({ skuId: item.skuId, quantity: item.quantity }))
        });
      })
      .then(order => {
        createdOrder = order;
        if (this.data.source === 'cart') {
          mallCart.rememberSettlement(order.orderNo, settlementItems, paymentUserId);
        }
        if (paymentUserId !== auth.getUserId()) throw new Error('登录账号已变化，请重新结算');
        const quotedAmount = Number(this.checkoutQuote && this.checkoutQuote.payableAmount) || 0;
        const serverAmount = Number(order.paidAmount || order.totalAmount) || 0;
        if (quotedAmount > 0 && quotedAmount !== serverAmount) {
          throw new Error('商品价格已更新，请刷新结算页后重新提交');
        }
        const amountFen = serverAmount;
        if (mall.normalizeStatus(order.payChannel || payChannel) === 'STORED_VALUE') {
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
        this.finishPayment(createdOrder, result, paymentUserId);
      })
      .catch(error => {
        wx.hideLoading();
        if (createdOrder && createdOrder.orderNo) {
          const message = error && error.res && Number(error.res.code) === 400 && error.message;
          wx.showToast({ title: message || '订单已创建，请继续支付', icon: 'none' });
          setTimeout(() => this.redirectToOrder(createdOrder.orderNo), 500);
          return;
        }
        throw error;
      });
  },

  finishPayment(order, result, paymentUserId) {
    const paid = Boolean(result && result.paid);
    if (paid) {
      mallCart.settlePaidOrder(order.orderNo, paymentUserId);
      wx.showToast({ title: '支付成功', icon: 'success' });
    } else {
      wx.showToast({ title: '订单待支付', icon: 'none' });
    }
    setTimeout(() => this.redirectToOrder(order.orderNo, paid), paid ? 400 : 600);
  },

  redirectToOrder(orderNo, fromPayment) {
    if (this.redirected || !orderNo) return;
    this.redirected = true;
    const paymentQuery = fromPayment ? '&fromPayment=1' : '';
    wx.redirectTo({ url: `/packageMall/pages/order-detail/order-detail?orderNo=${orderNo}${paymentQuery}` });
  },

  goLogin() {
    loginGuard.requireLogin(() => this.initialize(), { reason: '登录后可以结算' });
  },

  retry() {
    this.initialize();
  }
});
