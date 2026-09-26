const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { formatOrderAmount } = require('../../data/mock');
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');
const { getUserProfile, refreshUserProfileFromRemote } = require('../../utils/user-profile');
const { calcMemberPrice } = require('../../utils/member-level');

function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

/**
 * 「元」转「分」并取整。
 *
 * 为什么需要：前端购物车里的价格经 normalizeSpecProduct 已换算为「元」
 * （1390 分 -> 13.9 元），而后端接口 clientAmount 的语义是「分」。
 * 若直接传元值，后端会得到 13.9 与 1600 相减的巨大差额，
 * 导致 priceCheck.correct 永远为 false —— 校验形同虚设。
 */
function toFen(yuan) {
  return Math.round(Number(yuan || 0) * 100);
}

/**
 * 合计商品金额。
 *
 * 会员价必须 = 商品原价 × 等级折扣，与下列三处保持同一口径：
 *   · product-card（列表卡片）
 *   · spec-sheet（规格弹层）
 *   · 后端 OrderService（下单实收，权威）
 *
 * 历史 bug：这里原本直接用 item.price，但 item.price 本身就是「会员价基数」
 * （seed 里 classic-003 为 1590 分 = ¥15.9），并未打折。于是出现
 * 「列表 ¥14.4 → 确认页 ¥15.9 → 实收 ¥14.4」的跳变，用户会认为被多收钱。
 * 现在统一走 calcMemberPrice(原价, vipLevel)，全链路金额一致。
 */
function summarize(items, paymentMethod) {
  const count = items.reduce((sum, item) => sum + item.quantity, 0);
  const useStoredValue = paymentMethod === 'stored-value';
  const vipLevel = getUserProfile().vipLevel || '';
  // 储值支付：在会员价基础上再减「储值立减」（storedValuePrice 是该立减额）
  const amount = roundMoney(
    items.reduce((sum, item) => {
      const listPrice = item.originalPrice || item.price;
      const memberPrice = calcMemberPrice(listPrice, vipLevel);
      const unitPrice = useStoredValue ? Math.max(0, memberPrice - (item.storedValuePrice || 0)) : memberPrice;
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
      // clientAmount：本地按会员价算出的「应付总额」，单位为分。
      // 仅作交叉校验 —— 后端一律自己按「商品原价 × 等级折扣」重算，
      // 金额以服务端为准；这里传值只是让后端能回告 correct，
      // 便于发现两端折扣口径漂移（例如折扣解析规则被改动）。
      // 注意：stored-value 支付有额外立减，与后端「会员价」口径不同，
      // 传了必然判为不一致，故该支付方式下不传金额。
      const clientAmount =
        this.data.paymentMethod === 'stored-value' ? null : toFen(summarize(this.data.items, 'wechat').amount);
      api
        .createOrder({
          storeSubjectId: 101,
          mealType: app && app.globalData.orderMode === 'dinein' ? 'dinein' : 'pickup',
          items,
          clientAmount
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

    /**
     * 储值余额支付：先在后端真实下单，再用余额支付该订单。
     *
     * 原实现只改本地 profile.balance（模拟），存在三个问题：
     *   1. 不产生订单、不产生支付记录，后台「支付记录」看不到这笔消费；
     *   2. 扣款只发生在本地存储，换个设备余额就"复原"，无法对账；
     *   3. 前端本地判断余额，可被绕过（篡改本地存储即可超支）。
     *
     * 现在改为：createOrder 建单 -> pay-by-balance 由服务端原子扣款并置已支付。
     * 余额校验与扣减都在服务端完成（SQL 条件保证不会扣成负数），
     * 前端只负责把后端返回的余额重新同步到本地展示。
     */
    submitWithStoredValue() {
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
      wx.showLoading({ title: '支付中', mask: true });
      api
        .createOrder({
          storeSubjectId: storeId,
          mealType: app && app.globalData.orderMode === 'dinein' ? 'dinein' : 'pickup',
          items,
          // 储值支付有额外立减，与后端「会员价」口径不同，传了必然判为不一致
          clientAmount: null
        })
        .then(order => {
          app.globalData.pendingOrder = order;
          return api.payOrderByBalance(order.orderNo);
        })
        .then(paid => {
          wx.hideLoading();
          // 余额以服务端为准：回读用户资料刷新本地展示值
          this.refreshProfileFromServer();
          wx.showToast({ title: '储值支付成功', icon: 'success' });
          setTimeout(() => {
            wx.navigateTo({ url: '/pages/order-detail/order-detail?orderNo=' + paid.orderNo });
          }, 800);
        })
        .catch(error => {
          wx.hideLoading();
          // 余额不足等业务错误由后端给出可读文案，直接展示
          wx.showToast({ title: (error && error.message) || '支付失败', icon: 'none' });
        });
    },

    /**
     * 从服务端刷新用户资料（余额已由后端扣减，本地仅作展示）。
     *
     * 复用 user-profile 的 refreshUserProfileFromRemote：
     * 后端 balance 是「分」，归一化为「元」的换算只该有一处
     * （见 normalizeRemoteProfile），页面自己再除一次会变成双重换算。
     */
    refreshProfileFromServer() {
      return refreshUserProfileFromRemote()
        .then(profile => {
          const app = getApp();
          if (app && app.globalData) app.globalData.points = (profile && profile.points) || 0;
          return profile;
        })
        .catch(() => {
          // 资料刷新失败不影响支付结果：订单已由服务端置为已支付
        });
    }
  })
);

