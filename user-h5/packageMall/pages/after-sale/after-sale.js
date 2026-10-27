const api = require('../../../utils/api');
const auth = require('../../../utils/auth');
const loginGuard = require('../../../utils/login-guard');
const mall = require('../../utils/mall');

Page({
  data: {
    orderNo: '',
    order: null,
    items: [],
    reason: '',
    loading: true,
    needLogin: false,
    error: '',
    submitting: false,
    refundAmountText: '0.00'
  },

  onLoad(options) {
    const orderNo = String((options && options.orderNo) || '').trim();
    this.setData({ orderNo });
    if (!orderNo) {
      this.setData({ loading: false, error: '订单号缺失' });
      return;
    }
    this.loadOrder();
  },

  onShow() {
    if (this.data.orderNo && auth.isLoggedIn() && this.data.order) this.loadOrder();
  },

  loadOrder() {
    if (!auth.isLoggedIn()) {
      this.setData({ loading: false, needLogin: true, error: '' });
      return;
    }
    this.setData({ loading: !this.data.order, needLogin: false, error: '' });
    Promise.all([api.fetchOrderDetail(this.data.orderNo), api.fetchOrderAfterSales(this.data.orderNo)])
      .then(([order, records]) => {
        if (!order || order.category !== 'mall' || order.orderType !== 'MALL') {
          throw new Error('该订单不是商城订单');
        }
        if (order.status !== 'SHIPPED' && order.status !== 'COMPLETED') {
          throw new Error('仅已发货或已完成订单可申请售后');
        }
        const activeAfterSale =
          order.activeAfterSale ||
          (Array.isArray(records) &&
            records.some(item => ['PENDING', 'APPROVED', 'REFUNDING'].includes(mall.normalizeStatus(item.status))));
        if (activeAfterSale) {
          throw new Error('该订单已有处理中的售后申请');
        }
        const items = (order.items || [])
          .map(item => {
            const quantity = Math.max(1, Number(item.quantity) || 1);
            const refundedQuantity = Math.max(0, Number(item.refundedQuantity) || 0);
            return Object.assign({}, item, {
              id: Number(item.id),
              name: String(item.name || '商城商品'),
              spec: String(item.spec || ''),
              image: mall.imageUrl(item.image),
              unitPrice: Number(item.unitPrice) || 0,
              maxQuantity: Math.max(0, quantity - refundedQuantity),
              selectedQuantity: 0,
              selected: false,
              unitPriceText: mall.priceText(Number(item.unitPrice) || 0)
            });
          })
          .filter(item => item.maxQuantity > 0);
        if (!items.length) {
          throw new Error('该订单商品均已申请退货');
        }
        this.setData({ order: mall.decorateOrder(order), items, loading: false, error: '' }, () =>
          this.refreshAmount()
        );
      })
      .catch(error => {
        this.setData({ loading: false, error: (error && error.message) || '售后申请加载失败' });
      });
  },

  toggleItem(event) {
    const id = Number(event.currentTarget.dataset.id);
    const items = this.data.items.map(item => {
      if (item.id !== id) return item;
      const selected = !item.selected;
      return Object.assign({}, item, { selected, selectedQuantity: selected ? 1 : 0 });
    });
    this.setData({ items }, () => this.refreshAmount());
  },

  changeQuantity(event) {
    const id = Number(event.currentTarget.dataset.id);
    const delta = Number(event.currentTarget.dataset.delta) || 0;
    const items = this.data.items.map(item => {
      if (item.id !== id || !item.selected) return item;
      const next = Math.min(item.maxQuantity, Math.max(1, item.selectedQuantity + delta));
      return Object.assign({}, item, { selectedQuantity: next });
    });
    this.setData({ items }, () => this.refreshAmount());
  },

  refreshAmount() {
    const amount = this.data.items.reduce((sum, item) => {
      return sum + (item.selected ? item.unitPrice * item.selectedQuantity : 0);
    }, 0);
    this.setData({ refundAmountText: mall.moneyText(amount) });
  },

  submitAfterSale() {
    if (this.data.submitting) return;
    const selected = this.data.items.filter(item => item.selected && item.selectedQuantity > 0);
    if (!selected.length) {
      wx.showToast({ title: '请选择退货商品', icon: 'none' });
      return;
    }
    const reason = String(this.data.reason || '').trim();
    if (!reason) {
      wx.showToast({ title: '请填写售后原因', icon: 'none' });
      return;
    }
    this.setData({ submitting: true });
    wx.showLoading({ title: '提交中', mask: true });
    api
      .createAfterSale(this.data.orderNo, {
        reason,
        items: selected.map(item => ({ orderItemId: item.id, quantity: item.selectedQuantity }))
      })
      .then(result => {
        wx.hideLoading();
        this.setData({ submitting: false });
        wx.showToast({ title: '售后申请已提交', icon: 'success' });
        wx.redirectTo({
          url: `/packageMall/pages/after-sale-detail/after-sale-detail?afterSaleNo=${encodeURIComponent(result.afterSaleNo)}`
        });
      })
      .catch(error => {
        wx.hideLoading();
        this.setData({ submitting: false });
        wx.showToast({ title: (error && error.message) || '提交失败', icon: 'none' });
      });
  },

  onReasonInput(event) {
    this.setData({ reason: String((event.detail && event.detail.value) || '') });
  },

  openAfterSales() {
    wx.navigateTo({
      url: `/packageMall/pages/after-sales/after-sales?orderNo=${encodeURIComponent(this.data.orderNo)}`
    });
  },

  retry() {
    this.loadOrder();
  },

  goLogin() {
    loginGuard.requireLogin(() => this.loadOrder(), { reason: '登录后可申请售后' });
  }
});
