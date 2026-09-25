const { withShare } = require('../../utils/share');
const { orderCategories } = require('../../data/mock');
const auth = require('../../utils/auth');
const loginGuard = require('../../utils/login-guard');
const { cancelOrderById, cancelPaidOrderById, filterOrders, getOrders, refreshOrdersFromRemote, tickOrderCountdowns } = require('../../utils/orders');

const timeTabs = [
  { id: 'today', label: '今日订单' },
  { id: 'history', label: '历史订单' }
];

Page(
  withShare({
    data: {
      timeTabs,
      activeTimeGroup: 'today',
      orderCategories,
      activeCategory: 'all',
      orders: [],
      filteredOrders: [],
      // 分页状态：每页 20 条，上拉加载更多
      orderPage: 1,
      hasMoreOrders: true,
      loadingMoreOrders: false,
      // 未登录时不请求接口，展示「请先登录」空状态
      needLogin: false
    },
    // Tab 页首次展示由 onShow 统一加载，避免初始化时重复请求
    onLoad() {},
    onShow() {
      if (this.getTabBar) this.getTabBar().setData({ selected: 3 });
      // 从下单 / 详情返回时重新从第一页拉取，避免看到过期列表
      this.loadOrders();
      this.startCountdown();
    },
    /** 重新加载首屏（重置分页）；未登录时不请求接口，展示登录引导 */
    loadOrders() {
      if (!auth.isLoggedIn()) {
        this.setData({ needLogin: true, orders: [], filteredOrders: [] });
        return;
      }
      this.setData({ needLogin: false });
      refreshOrdersFromRemote({ page: 1, timeGroup: this.data.activeTimeGroup, category: this.data.activeCategory }).then(res => {
        this.setData({ orderPage: 1, hasMoreOrders: Boolean(res && res.hasMore) });
        this.refreshOrders();
      });
    },
    /** 未登录空状态里的「去登录」入口 */
    goLogin() {
      loginGuard.requireLogin(() => this.loadOrders(), { reason: '登录后可查看订单' });
    },
    /** 上拉加载下一页 */
    onReachBottom() {
      if (!this.data.hasMoreOrders || this.data.loadingMoreOrders) return;
      const nextPage = this.data.orderPage + 1;
      this.setData({ loadingMoreOrders: true });
      refreshOrdersFromRemote({ page: nextPage, append: true, timeGroup: this.data.activeTimeGroup, category: this.data.activeCategory })
        .then(res => {
          this.setData({
            orderPage: nextPage,
            hasMoreOrders: Boolean(res && res.hasMore),
            loadingMoreOrders: false
          });
          this.refreshOrders();
        })
        .catch(() => this.setData({ loadingMoreOrders: false }));
    },
    onHide() {
      this.stopCountdown();
    },
    onUnload() {
      this.stopCountdown();
    },
    refreshOrders() {
      const orders = getOrders();
      this.setData({
        orders,
        filteredOrders: filterOrders(orders, this.data.activeTimeGroup, this.data.activeCategory)
      });
    },
    startCountdown() {
      this.stopCountdown();
      this.countdownTimer = setInterval(() => {
        tickOrderCountdowns();
        this.refreshOrders();
      }, 1000);
    },
    stopCountdown() {
      if (this.countdownTimer) clearInterval(this.countdownTimer);
      this.countdownTimer = null;
    },
    selectTimeGroup(event) {
      const group = event.currentTarget.dataset.group;
      this.setData({ activeTimeGroup: group });
      this.loadOrders();
    },
    selectCategory(event) {
      const { id } = event.currentTarget.dataset;
      this.setData({ activeCategory: id });
      this.loadOrders();
    },
    showInvoice() {
      wx.showToast({ title: '开发票暂未接入', icon: 'none' });
    },
    openOrderDetail(event) {
      const { id } = event.currentTarget.dataset;
      wx.navigateTo({ url: `/pages/order-detail/order-detail?id=${id}` });
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
              this.refreshOrders();
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
              this.refreshOrders();
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
    showUnavailable() {
      wx.showToast({ title: '功能暂未接入', icon: 'none' });
    }
  })
);

