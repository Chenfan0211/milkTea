const { withShare } = require('../../utils/share');
const {
  cancelOrderById,
  cancelPaidOrderById,
  fetchOrderFromRemote,
  getOrderById,
  tickOrderCountdowns
} = require('../../utils/orders');
const { verifyExchange } = require('../../utils/points');

Page(
  withShare({
    data: {
      orderId: '',
      order: {}
    },
    /**
     * 进入详情页：先渲染本地镜像（有则秒出），无则请求后端单查。
     *
     * 为什么不能只读本地：orderStore 由列表页按时间页签 + 分类分批填充，
     * 从分享 / 消息 / 订单提醒等入口直接进入时镜像是空的，
     * 旧实现会直接提示「订单不存在」并返回，表现为详情页没数据。
     */
    onLoad(options) {
      const orderId = options && options.id ? options.id : '';
      this.setData({ orderId });
      const local = getOrderById(orderId);
      if (local) this.setData({ order: local });
      fetchOrderFromRemote(orderId).then(order => {
        if (!order) {
          wx.showToast({ title: '订单不存在', icon: 'none' });
          setTimeout(() => wx.navigateBack(), 500);
          return;
        }
        // 只负责渲染数据；倒计时统一由 onShow 启动（onLoad 之后必然触发），
        // 避免在异步回调里重复管理定时器生命周期。
        this.setData({ order });
      });
    },
    onShow() {
      if (!this.data.orderId) return;
      this.refreshOrder();
      if (this.data.order && this.data.order.id) this.startCountdown();
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
          wx.showLoading({ title: '取消中', mask: true });
          cancelOrderById(id)
            .then(() => {
              wx.hideLoading();
              const order = getOrderById(id);
              this.setData({ order });
              wx.showToast({ title: '订单已取消', icon: 'none' });
            })
            .catch(error => {
              wx.hideLoading();
              wx.showToast({ title: (error && error.message) || '取消失败，请重试', icon: 'none' });
            });
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
          wx.showLoading({ title: '取消中', mask: true });
          cancelPaidOrderById(id)
            .then(() => {
              wx.hideLoading();
              const order = getOrderById(id);
              this.setData({ order });
              wx.showToast({ title: '已取消，退款原路退回', icon: 'none' });
            })
            .catch(error => {
              wx.hideLoading();
              wx.showToast({ title: (error && error.message) || '取消失败，请重试', icon: 'none' });
            });
        }
      });
    },
    handlePay() {
      wx.showToast({ title: '支付功能暂未接入', icon: 'none' });
    },
    handleReorder() {
      wx.showToast({ title: '再次购买暂未接入', icon: 'none' });
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
          // 核销为服务端写操作：结果以后端为准（重复核销 / 未支付会被拒绝）
          verifyExchange(expected).then(result => {
            if (!result || result.ok !== true) {
              const reason =
                result && result.reason === 'already_verified'
                  ? '该码已核销'
                  : (result && result.message) || '核销失败';
              wx.showToast({ title: reason, icon: 'none' });
              return;
            }
            wx.showToast({ title: '核销成功', icon: 'success' });
            this.refreshOrder();
          });
        },
        fail: () => {
          wx.showToast({ title: '扫码已取消', icon: 'none' });
        }
      });
    },
    handleCopy() {
      const detail = this.data.order || {};
      // 兼容后端扁平 orderNo 与归一化后的 orderInfo.orderNo 两种结构
      const orderNo = (detail.orderInfo && detail.orderInfo.orderNo) || detail.orderNo;
      if (!orderNo) return;
      wx.setClipboardData({ data: orderNo });
    }
  })
);
