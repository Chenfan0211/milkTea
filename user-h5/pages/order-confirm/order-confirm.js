const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { formatOrderAmount } = require('../../data/mock');
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');
const { getUserProfile, saveUserProfile } = require('../../utils/user-profile');

function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

function summarize(items, paymentMethod) {
  const count = items.reduce((sum, item) => sum + item.quantity, 0);
  const useStoredValue = paymentMethod === 'stored-value';
  const amount = roundMoney(
    items.reduce((sum, item) => {
      const unitPrice = useStoredValue ? item.storedValuePrice || item.price : item.price;
      return sum + unitPrice * item.quantity;
    }, 0)
  );
  const original = roundMoney(items.reduce((sum, item) => sum + (item.originalPrice || item.price) * item.quantity, 0));
  return {
    count,
    amount,
    discount: roundMoney(original - amount)
  };
}

Page(
  withShare({
    data: {
      store: {},
      items: [],
      orderMode: 'pickup',
      modeOptions: [],
      paymentMethod: 'wechat',
      count: 0,
      amountText: '0',
      discountText: '0',
      storedAmountText: '0',
      pointCount: 0,
      phone: '',
      remark: ''
    },
    onLoad() {
      const app = getApp();
      const pending = app.globalData.pendingOrder;
      if (!pending || !pending.items || !pending.items.length) {
        wx.showToast({ title: '暂无结算商品', icon: 'none' });
        setTimeout(() => wx.navigateBack(), 800);
        return;
      }
      const catalog = resolveStoreCatalog();
      const store =
        catalog.stores.find(item => item.id === pending.storeId) || catalog.currentStore || catalog.stores[0];
      const orderMode = pending.orderMode || app.globalData.orderMode || 'pickup';
      this.applyOrder(pending.items, store, orderMode);
    },
    applyOrder(items, store, orderMode, paymentMethod) {
      const nextPaymentMethod = paymentMethod || this.data.paymentMethod || 'wechat';
      const summary = summarize(items, nextPaymentMethod);
      const storedSummary = summarize(items, 'stored-value');
      const storedDiscountTotal = roundMoney(summary.amount - storedSummary.amount);
      this.setData({
        items,
        store,
        orderMode,
        paymentMethod: nextPaymentMethod,
        modeOptions: [
          {
            value: 'dinein',
            label: '店内就餐',
            icon: '/assets/icons/lucide/dine-in.svg',
            disabled: store.modes.indexOf('dinein') === -1
          },
          {
            value: 'pickup',
            label: '打包外带',
            icon: '/assets/icons/lucide/takeaway.svg',
            disabled: store.modes.indexOf('pickup') === -1
          }
        ],
        count: summary.count,
        amountText: formatOrderAmount(summary.amount),
        discountText: formatOrderAmount(summary.discount),
        storedAmountText: formatOrderAmount(storedDiscountTotal),
        pointCount: Math.floor(summary.amount)
      });
    },
    selectMode(event) {
      const { mode } = event.currentTarget.dataset;
      const option = this.data.modeOptions.find(item => item.value === mode);
      if (!option || option.disabled) {
        wx.showToast({ title: '当前门店暂不支持该方式', icon: 'none' });
        return;
      }
      getApp().globalData.orderMode = mode;
      this.setData({ orderMode: mode });
    },
    selectStore() {
      const catalog = resolveStoreCatalog();
      const availableStores = catalog.stores;
      wx.showActionSheet({
        itemList: availableStores.map(store => store.name),
        success: ({ tapIndex }) => {
          const store = availableStores[tapIndex];
          persistSelectedStore(store.id);
          const app = getApp();
          app.globalData.selectedStoreId = store.id;
          let orderMode = this.data.orderMode;
          if (store.modes.indexOf(orderMode) === -1) orderMode = store.modes[0];
          app.globalData.orderMode = orderMode;
          this.applyOrder(this.data.items, store, orderMode, this.data.paymentMethod);
        }
      });
    },
    selectPaymentMethod(event) {
      const { method } = event.currentTarget.dataset;
      if (method === this.data.paymentMethod) return;
      this.applyOrder(this.data.items, this.data.store, this.data.orderMode, method);
    },
    handlePhoneInput(event) {
      this.setData({ phone: event.detail.value });
    },
    handleRemarkInput(event) {
      this.setData({ remark: event.detail.value });
    },
    handleCoupon() {
      wx.showToast({ title: '优惠券暂未接入', icon: 'none' });
    },

    /**
     * 提交订单。
     * 手机号强制：未绑定手机号不能下单，授权完成后自动续跑本次提交。
     */
    handleSubmit() {
      loginGuard.requirePhone(() => this.doSubmit(), {
        reason: '下单需要绑定手机号，用于订单通知与售后'
      });
    },

    /** 实际提交（登录且已绑手机号后执行） */
    doSubmit() {
      if (this.data.paymentMethod === 'stored-value') {
        this.submitWithStoredValue();
        return;
      }
      // 微信支付：正式接入后替换为真实下单 + 支付
      this.createOrderAndPay();
    },

    /** 调用后端下单（真实接口） */
    createOrderAndPay() {
      const app = getApp();
      const storeId = (this.data.store && this.data.store.id) || (app && app.globalData.selectedStoreId);
      const items = (this.data.items || []).map(item => ({
        productId: item.productId || item.id,
        quantity: item.quantity || 1,
        spec: item.spec || ''
      }));
      if (!items.length) {
        wx.showToast({ title: '购物车为空', icon: 'none' });
        return;
      }
      wx.showLoading({ title: '提交中', mask: true });
      api
        .createOrder({
          storeSubjectId: 101,
          mealType: app && app.globalData.orderMode === 'dinein' ? 'dinein' : 'pickup',
          items
        })
        .then(order => {
          wx.hideLoading();
          app.globalData.pendingOrder = order;
          wx.showToast({ title: '下单成功', icon: 'success' });
          setTimeout(() => {
            wx.navigateTo({ url: '/pages/order-detail/order-detail?orderNo=' + order.orderNo });
          }, 800);
        })
        .catch(error => {
          wx.hideLoading();
          wx.showToast({ title: (error && error.message) || '下单失败', icon: 'none' });
        });
    },

    submitWithStoredValue() {
      const summary = summarize(this.data.items, 'stored-value');
      const profile = getUserProfile();
      const balance = Number(profile.balance || 0);
      if (balance < summary.amount) {
        wx.showToast({ title: '余额不足，请先充值', icon: 'none' });
        return;
      }
      const nextBalance = roundMoney(balance - summary.amount);
      // 下单实付金额累计成长值（1 元 = 1 成长值），同时发放等额时光币（1 元 = 1 时光币）。
      const earned = Math.floor(summary.amount);
      const nextTotalSpend = roundMoney((Number(profile.totalSpend) || 0) + summary.amount);
      const nextPoints = (Number(profile.points) || 0) + earned;
      saveUserProfile(
        Object.assign({}, profile, {
          balance: nextBalance,
          totalSpend: nextTotalSpend,
          points: nextPoints
        })
      );
      const app = getApp();
      if (app && app.globalData) app.globalData.points = nextPoints;
      app.globalData.pendingOrder = null;
      wx.showToast({ title: '储值支付成功（模拟）', icon: 'none' });
      setTimeout(() => {
        wx.navigateBack();
      }, 800);
    }
  })
);

