const loginGuard = require('../../utils/login-guard');
const api = require('../../utils/api');
const { withShare } = require('../../utils/share');
const { formatOrderAmount } = require('../../data/mock');
const { resolveStoreCatalog, selectStore: persistSelectedStore } = require('../../utils/store');
const { getUserProfile, refreshUserProfileFromRemote } = require('../../utils/user-profile');
const { calcMemberPrice } = require('../../utils/member-level');
const { refreshCouponsFromRemote } = require('../../utils/coupons');

function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

function toFen(yuan) {
  return Math.round(Number(yuan || 0) * 100);
}

function sameId(left, right) {
  return left != null && right != null && String(left) === String(right);
}

function parseDateTime(value) {
  const text = String(value || '').trim().replace('T', ' ');
  const match = text.match(/^(\d{4})-(\d{2})-(\d{2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?/);
  if (!match) return null;
  const date = new Date(
    Number(match[1]),
    Number(match[2]) - 1,
    Number(match[3]),
    Number(match[4] == null ? 23 : match[4]),
    Number(match[5] == null ? 59 : match[5]),
    Number(match[6] == null ? 59 : match[6])
  );
  return Number.isNaN(date.getTime()) ? null : date;
}

function isCouponExpired(coupon, now) {
  if (!coupon || coupon.expired || coupon.usable === false) return true;
  const end = parseDateTime(coupon.validityEnd || coupon.expireAt);
  return Boolean(end && now.getTime() > end.getTime());
}

function parseTimeToSeconds(value) {
  const match = String(value || '').trim().match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  const second = Number(match[3] || 0);
  if (hour > 23 || minute > 59 || second > 59) return null;
  return hour * 3600 + minute * 60 + second;
}

function isUsageTimeMatched(usageTime, now) {
  const text = String(usageTime || '').trim();
  if (!text) return true;
  const match = text.match(/^([0-9:]+)\s*[~～]\s*([0-9:]+)$/);
  if (!match) return true;
  const start = parseTimeToSeconds(match[1]);
  const end = parseTimeToSeconds(match[2]);
  if (start == null || end == null) return true;
  const current = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();
  if (start <= end) return current >= start && current <= end;
  return current >= start || current <= end;
}

function isSceneMatched(scenes, orderMode) {
  const value = Array.isArray(scenes) ? scenes.join(' ') : String(scenes || '');
  const text = value.trim().toLowerCase();
  if (!text) return true;
  const aliases =
    orderMode === 'dinein'
      ? ['dinein', '店内就餐', '门店就餐', '堂食']
      : ['pickup', '打包外带', '外带', '打包'];
  return aliases.some(alias => text.indexOf(alias.toLowerCase()) !== -1);
}

function couponAmountFen(coupon) {
  if (!coupon) return 0;
  const exact = Number(coupon.amountFen);
  if (Number.isFinite(exact)) return Math.max(0, Math.round(exact));
  return Math.max(0, toFen(coupon.amount));
}

function couponThresholdFen(coupon) {
  if (!coupon) return 0;
  const exact = Number(coupon.thresholdFen);
  if (Number.isFinite(exact)) return Math.max(0, Math.round(exact));
  return Math.max(0, toFen(coupon.threshold));
}

function calculateOrder(items, paymentMethod, coupon) {
  const list = Array.isArray(items) ? items : [];
  const useStoredValue = paymentMethod === 'stored-value';
  const vipLevel = getUserProfile().vipLevel || '';
  let count = 0;
  let originalFen = 0;
  let memberTotalFen = 0;
  let storedValueDiscountFen = 0;

  list.forEach(item => {
    const quantity = Math.max(0, Number(item.quantity) || 0);
    const listPrice = Number(item.originalPrice || item.price || 0);
    const memberUnitFen = toFen(calcMemberPrice(listPrice, vipLevel));
    count += quantity;
    originalFen += toFen(listPrice) * quantity;
    memberTotalFen += memberUnitFen * quantity;
    if (useStoredValue) {
      const unitDiscountFen = toFen(item.storedValueDiscount || 0);
      storedValueDiscountFen += Math.min(memberUnitFen, unitDiscountFen) * quantity;
    }
  });

  const amountBeforeCouponFen = Math.max(0, memberTotalFen - storedValueDiscountFen);
  const couponDiscountFen = coupon
    ? Math.min(couponAmountFen(coupon), amountBeforeCouponFen)
    : 0;
  const paidAmountFen = Math.max(0, amountBeforeCouponFen - couponDiscountFen);

  return {
    count,
    originalFen,
    memberTotalFen,
    storedValueDiscountFen,
    amountBeforeCouponFen,
    couponDiscountFen,
    paidAmountFen,
    amount: paidAmountFen / 100,
    discount: Math.max(0, originalFen - paidAmountFen) / 100
  };
}

function isCouponUsable(coupon, items, store, orderMode, amountBeforeCouponFen) {
  if (!coupon || isCouponExpired(coupon, new Date())) return false;
  if (!isUsageTimeMatched(coupon.usageTime, new Date())) return false;
  if (!isSceneMatched(coupon.scenes, orderMode)) return false;
  if (couponThresholdFen(coupon) > amountBeforeCouponFen) return false;

  // 券的适用门店是后端数字 subjectId，而前端门店 id 是业务 code，
  // 因此必须用 store.subjectId 比对，否则限定门店的券会永远匹配不上。
  const storeIds = Array.isArray(coupon.applicableStoreIds) ? coupon.applicableStoreIds : [];
  if (storeIds.length && !storeIds.some(id => sameId(id, store && store.subjectId))) return false;

  const productIds = Array.isArray(coupon.applicableProductIds) ? coupon.applicableProductIds : [];
  if (productIds.length) {
    const matched = (items || []).some(item =>
      productIds.some(id => sameId(id, item.productId || item.id))
    );
    if (!matched) return false;
  }
  return true;
}

function couponTitle(coupon) {
  const title = (coupon && (coupon.title || coupon.condition)) || '优惠券';
  return `${title}（-¥${formatOrderAmount(couponAmountFen(coupon) / 100)}）`;
}

function couponText(coupon, discountFen) {
  if (!coupon) return '暂无可用优惠券';
  const title = coupon.title || coupon.condition || '优惠券';
  return `已选 ${title} -¥${formatOrderAmount((Number(discountFen) || 0) / 100)}`;
}

function backendCouponId(coupon) {
  if (!coupon || coupon.id == null) return null;
  const id = Number(coupon.id);
  return Number.isFinite(id) ? id : null;
}

function pickBestCoupon(coupons) {
  const list = Array.isArray(coupons) ? coupons : [];
  if (!list.length) return null;
  return list
    .slice()
    .sort((a, b) => {
      const diff = couponAmountFen(b) - couponAmountFen(a);
      if (diff !== 0) return diff;
      const aEnd = parseDateTime(a.validityEnd || a.expireAt);
      const bEnd = parseDateTime(b.validityEnd || b.expireAt);
      if (!aEnd && bEnd) return 1;
      if (aEnd && !bEnd) return -1;
      if (aEnd && bEnd && aEnd.getTime() !== bEnd.getTime()) {
        return aEnd.getTime() - bEnd.getTime();
      }
      return 0;
    })[0] || null;
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
      remark: '',
      coupons: [],
      usableCoupons: [],
      selectedCoupon: null,
      couponManuallyChanged: false,
      couponText: '暂无可用优惠券',
      amountBeforeCouponFen: 0,
      couponDiscountFen: 0,
      paidAmountFen: 0
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
      // 缓存本次结算来源与条目 id，供支付成功后清空购物车（避免 pendingOrder 被后端订单覆盖丢失）
      this.settledFromCart = pending.fromCart === true;
      this.settledCartIds = (pending.items || []).map(item => item.id);
      this.applyOrder(pending.items, store, orderMode);
      this.loadCoupons();
    },

    applyOrder(items, store, orderMode, paymentMethod) {
      const nextPaymentMethod = paymentMethod || this.data.paymentMethod || 'wechat';
      const modes = Array.isArray(store.modes) ? store.modes : [];
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
            disabled: modes.indexOf('dinein') === -1
          },
          {
            value: 'pickup',
            label: '打包外带',
            icon: '/assets/icons/lucide/takeaway.svg',
            disabled: modes.indexOf('pickup') === -1
          }
        ]
      });
      this.refreshSummary();
    },

    loadCoupons() {
      return refreshCouponsFromRemote('UNUSED')
        .then(list => {
          this.setData({ coupons: Array.isArray(list) ? list : [] });
          this.refreshSummary();
        })
        .catch(() => {
          this.setData({ coupons: [] });
          this.refreshSummary();
        });
    },

    getUsableCoupons(amountBeforeCouponFen) {
      const amount =
        typeof amountBeforeCouponFen === 'number'
          ? amountBeforeCouponFen
          : calculateOrder(this.data.items, this.data.paymentMethod, null).amountBeforeCouponFen;
      return (this.data.coupons || []).filter(coupon =>
        isCouponUsable(coupon, this.data.items, this.data.store, this.data.orderMode, amount)
      );
    },

    refreshSummary() {
      const usableCoupons = this.getUsableCoupons();
      const current = this.data.selectedCoupon;
      let selectedCoupon =
        current && usableCoupons.some(coupon => sameId(coupon.id, current.id)) ? current : null;
      if (!this.data.couponManuallyChanged) {
        selectedCoupon = pickBestCoupon(usableCoupons);
      }
      const summary = calculateOrder(this.data.items, this.data.paymentMethod, selectedCoupon);
      // 储值支付选项的立减提示始终按储值口径计算，与当前选中的支付方式无关，
      // 避免在微信支付状态下显示「储值立减 ¥0」误导用户。
      const storedSummary = calculateOrder(this.data.items, 'stored-value', selectedCoupon);
      this.setData({
        usableCoupons,
        selectedCoupon,
        couponText: selectedCoupon
          ? couponText(selectedCoupon, summary.couponDiscountFen)
          : usableCoupons.length
            ? `可用 ${usableCoupons.length} 张`
            : '暂无可用优惠券',
        count: summary.count,
        amountText: formatOrderAmount(summary.amount),
        discountText: formatOrderAmount(summary.discount),
        storedAmountText: formatOrderAmount(storedSummary.storedValueDiscountFen / 100),
        pointCount: Math.floor(summary.amount),
        amountBeforeCouponFen: summary.amountBeforeCouponFen,
        couponDiscountFen: summary.couponDiscountFen,
        paidAmountFen: summary.paidAmountFen
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
      this.refreshSummary();
    },

    selectStore() {
      const catalog = resolveStoreCatalog();
      const availableStores = catalog.stores;
      wx.showActionSheet({
        itemList: availableStores.map(store => store.name),
        success: ({ tapIndex }) => {
          const store = availableStores[tapIndex];
          if (!store) return;
          persistSelectedStore(store.id);
          const app = getApp();
          app.globalData.selectedStoreId = store.id;
          let orderMode = this.data.orderMode;
          if ((store.modes || []).indexOf(orderMode) === -1) orderMode = (store.modes || [])[0];
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
      const usableCoupons = this.getUsableCoupons();
      if (!usableCoupons.length) {
        this.setData({ selectedCoupon: null });
        this.refreshSummary();
        wx.showToast({ title: '暂无可用优惠券', icon: 'none' });
        return;
      }
      wx.showActionSheet({
        itemList: ['不使用优惠券'].concat(usableCoupons.map(couponTitle)),
        success: ({ tapIndex }) => {
          const selectedCoupon = tapIndex === 0 ? null : usableCoupons[tapIndex - 1] || null;
          this.setData({ selectedCoupon, couponManuallyChanged: true });
          this.refreshSummary();
        }
      });
    },

    /**
     * 提交订单。手机号强制：未绑定手机号不能下单，授权完成后自动续跑本次提交。
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
      this.createOrderAndPay();
    },

    buildOrderItems() {
      return (this.data.items || []).map(item => ({
        productId: item.productId || item.id,
        quantity: item.quantity || 1,
        spec: item.spec || ''
      }));
    },

    /**
     * 取下单用的门店数字主键（storeSubjectId）。
     *
     * 关键（原 bug）：前端门店 id 用的是业务 code（如 ST-1001），
     * 后端 storeSubjectId 是 Long（如 101），直接传 code 会导致
     * Jackson 反序列化失败并返回 500「服务器内部错误」。
     * 因此这里必须取 subjectId（数字主键），必要时按 id 反查门店目录。
     */
    resolveStoreSubjectId() {
      const store = this.data.store;
      if (store && store.subjectId != null) return Number(store.subjectId);
      const app = getApp();
      const storeId = (store && store.id) || (app && app.globalData.selectedStoreId);
      if (storeId == null) return null;
      // 兼容 store.id 已是数字主键的场景（如部分本地夹具）
      if (typeof storeId === 'number' && Number.isInteger(storeId)) return storeId;
      const catalog = resolveStoreCatalog();
      const matched = (catalog.stores || []).find(item => String(item.id) === String(storeId));
      if (matched && matched.subjectId != null) return Number(matched.subjectId);
      return null;
    },

    buildOrderPayload() {
      const summary = calculateOrder(this.data.items, this.data.paymentMethod, this.data.selectedCoupon);
      const storedValuePay = this.data.paymentMethod === 'stored-value';
      return {
        storeSubjectId: this.resolveStoreSubjectId(),
        mealType: this.data.orderMode === 'dinein' ? 'dinein' : 'pickup',
        items: this.buildOrderItems(),
        payChannel: storedValuePay ? 'STORED_VALUE' : 'WXPAY',
        userCouponId: backendCouponId(this.data.selectedCoupon),
        // clientAmount 是服务端会员价口径，储值立减另由 payChannel 计算。
        clientAmount: storedValuePay ? null : summary.memberTotalFen,
        clientPaidAmount: summary.paidAmountFen
      };
    },

    /** 微信支付下单：只创建订单，不在此处调用任何预支付接口。 */
    createOrderAndPay() {
      const payload = this.buildOrderPayload();
      if (!payload.items.length) {
        wx.showToast({ title: '购物车为空', icon: 'none' });
        return;
      }
      wx.showLoading({ title: '提交中', mask: true });
      api
        .createOrder(payload)
        .then(order => {
          const app = getApp();
          wx.hideLoading();
          app.globalData.pendingOrder = order;
          this.markSettledCart();
          wx.showToast({ title: '下单成功', icon: 'success' });
          setTimeout(() => {
            wx.redirectTo({ url: '/pages/pay-success/pay-success?orderNo=' + order.orderNo });
          }, 800);
        })
        .catch(error => {
          wx.hideLoading();
          wx.showToast({ title: (error && error.message) || '下单失败', icon: 'none' });
        });
    },

    /**
     * 储值余额支付：createOrder 建单后调用 payOrderByBalance 服务端原子扣款。
     * 不调用微信预支付接口，支付成功后跳转订单详情刷新后端状态。
     */
    submitWithStoredValue() {
      const payload = this.buildOrderPayload();
      if (!payload.items.length) {
        wx.showToast({ title: '购物车为空', icon: 'none' });
        return;
      }
      wx.showLoading({ title: '支付中', mask: true });
      api
        .createOrder(payload)
        .then(order => {
          const app = getApp();
          app.globalData.pendingOrder = order;
          return api.payOrderByBalance(order.orderNo);
        })
        .then(paid => {
          wx.hideLoading();
          this.refreshProfileFromServer();
          this.markSettledCart();
          wx.showToast({ title: '储值支付成功', icon: 'success' });
          setTimeout(() => {
            wx.redirectTo({ url: '/pages/pay-success/pay-success?orderNo=' + paid.orderNo });
          }, 800);
        })
        .catch(error => {
          wx.hideLoading();
          wx.showToast({ title: (error && error.message) || '支付失败', icon: 'none' });
        });
    },

    /**
     * 支付成功后，若本次订单来自购物车结算，则标记需要移除的购物车条目，
     * 由点单页 onShow 消费。立即购买（fromCart=false）不写标记。
     */
    markSettledCart() {
      const app = getApp();
      if (!this.settledFromCart) return;
      app.globalData.settledCartIds = this.settledCartIds || [];
    },

    /**
     * 从服务端刷新用户资料（余额已由后端扣减，本地仅作展示）。
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

