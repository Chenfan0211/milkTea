const { withShare } = require('../../utils/share');
const { cancelOrderById, cancelPaidOrderById, getOrderById, tickOrderCountdowns } = require('../../utils/orders');
const { verifyExchange } = require('../../utils/points');

Page(
  withShare({
    data: {
      orderId: '',
      order: {}
    },
    onLoad(options) {
      const order = getOrderById(options.id);
      if (!order) {
        wx.showToast({ title: '订单不存在', icon: 'none' });
        setTimeout(() => wx.navigateBack(), 500);
        return;
      }
      this.setData({ orderId: options.id, order });
    },
    onShow() {
      if (!this.data.orderId) return;
      this.refreshOrder();
      this.startCountdown();
    },
    onHide() {
      this.stopCountdown();
    },
    onUnload() {
      this.stopCountdown();
    },
    refreshOrder() {
      const order = getOrderById(this.data.orderId);
      if (order) this.setData({ order });
    },
    startCountdown() {
      this.stopCountdown();
      this.countdownTimer = setInterval(() => {
        tickOrderCountdowns();
        this.refreshOrder();
      }, 1000);
    },
    stopCountdown() {
      if (this.countdownTimer) clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    },
    cancelOrder(event) {
      const { id } = event.currentTarget.dataset;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该订单吗？',
        success: ({ confirm }) => {
          if (!confirm) return;
          const order = cancelOrderById(id);
          this.setData({ order });
          wx.showToast({ title: '订单已取消', icon: 'none' });
        }
      });
    },
    cancelPaidOrder(event) {
      const { id } = event.currentTarget.dataset;
      wx.showModal({
        title: '取消订单',
        content: '确定取消该订单？款项将原路退回',
        success: ({ confirm }) => {
          if (!confirm) return;
          const order = cancelPaidOrderById(id);
          this.setData({ order });
          wx.showToast({ title: '已取消，退款原路退回', icon: 'none' });
        }
      });
    },
    handlePay() {
      wx.showToast({ title: '支付功能暂未接入', icon: 'none' });
    },
    handleReorder() {
      wx.showToast({ title: '再次购买暂未接入', icon: 'none' });
    },
    handleReview() {
      wx.showToast({ title: '评价功能暂未接入', icon: 'none' });
    },
    copyVerifyCode() {
      const code = this.data.order.pickupCode;
      if (!code) return;
      wx.setClipboardData({ data: code });
    },
    scanVerifyCode() {
      const expected = this.data.order.pickupCode;
      if (!expected) return;
      wx.scanCode({
        success: (res) => {
          const scanned = String((res && res.result) || '').trim();
          if (!scanned) {
            wx.showToast({ title: '未识别到核销码', icon: 'none' });
            return;
          }
          if (scanned !== expected) {
            wx.showToast({ title: '核销码不匹配', icon: 'none' });
            return;
          }
          const result = verifyExchange(expected);
          if (!result || result.ok !== true) {
            const reason = result && result.reason === 'already_verified' ? '该码已核销' : '核销失败';
            wx.showToast({ title: reason, icon: 'none' });
            return;
          }
          wx.showToast({ title: '核销成功', icon: 'success' });
          this.refreshOrder();
        },
        fail: () => {
          wx.showToast({ title: '扫码已取消', icon: 'none' });
        }
      });
    },
    handleCopy() {
      const orderNo = this.data.order.orderInfo && this.data.order.orderInfo.orderNo;
      if (!orderNo) return;
      wx.setClipboardData({ data: orderNo });
    }
  })
);
