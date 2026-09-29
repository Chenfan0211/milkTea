const { withShare } = require('../../utils/share');
const { refreshCouponsFromRemote } = require('../../utils/coupons');

/**
 * 券列表 Tab：文案 + 后端 status 枚举。
 * 「待生效」对应领券时使用时间尚未开始的券 —— 后端 myCoupons 会把这类券
 * 视为不可用（validity.usable() === false），凭 UNUSED + 未生效时间区分。
 */
const COUPON_TABS = [
  { value: 'UNUSED', label: '未使用' },
  { value: 'USED', label: '已使用' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'PENDING', label: '待生效' }
];

const USABLE_TABS = ['UNUSED', 'PENDING'];

const EMPTY_STATES = {
  UNUSED: { title: '暂无可用优惠券', description: '去点单页看看，新获得的优惠券会展示在这里' },
  USED: { title: '暂无已使用优惠券', description: '使用过的优惠券会保留在这里，方便随时回查' },
  EXPIRED: { title: '暂无已过期优惠券', description: '过期的优惠券会归档在这里，便于核对有效期' },
  PENDING: { title: '暂无待生效优惠券', description: '使用时间未到的优惠券会展示在这里，生效后即可使用' }
};

function countCoupons(list) {
  return list.reduce((sum, item) => sum + item.quantity, 0);
}

Page(
  withShare({
    data: {
      tabs: COUPON_TABS,
      activeTab: 'UNUSED',
      coupons: [],
      couponCount: 0,
      emptyTitle: EMPTY_STATES.UNUSED.title,
      emptyDescription: EMPTY_STATES.UNUSED.description,
      loading: false,
      loadError: ''
    },
    onShow() {
      this.loadCoupons(this.data.activeTab);
    },
    loadCoupons(tab) {
      const status = tab || this.data.activeTab;
      const empty = EMPTY_STATES[status] || EMPTY_STATES.UNUSED;
      this.setData({
        activeTab: status,
        loading: true,
        loadError: '',
        emptyTitle: empty.title,
        emptyDescription: empty.description
      });
      return refreshCouponsFromRemote(status)
        .then(list => {
          // 切 Tab 期间可能已有更新的请求返回，丢弃过期响应避免串数据。
          if (this.data.activeTab !== status) return;
          const coupons = (Array.isArray(list) ? list : []).map(item => this.decorateCoupon(item, status));
          this.setData({
            coupons,
            couponCount: countCoupons(coupons),
            loading: false,
            loadError: ''
          });
        })
        .catch(() => {
          if (this.data.activeTab !== status) return;
          this.setData({
            coupons: [],
            loading: false,
            loadError: '优惠券加载失败，请检查网络后重试'
          });
        });
    },
    /** 按当前 Tab 决定卡片展示态：动作按钮 / 数量角标 / 禁用配色。 */
    decorateCoupon(coupon, status) {
      const usable = USABLE_TABS.indexOf(status) !== -1;
      return Object.assign({}, coupon, {
        expanded: false,
        usable,
        showCount: usable,
        disabled: !usable,
        statusText: usable ? '' : COUPON_TABS.filter(item => item.value === status).map(item => item.label)[0]
      });
    },
    switchTab(event) {
      const { tab } = event.currentTarget.dataset;
      if (!tab || tab === this.data.activeTab) return;
      // 立即清空旧列表，避免上一个 Tab 的券在新状态文案下闪现。
      this.setData({ coupons: [], couponCount: 0 });
      this.loadCoupons(tab);
    },
    retryLoad() {
      this.loadCoupons(this.data.activeTab);
    },
    toggleRules(event) {
      const { id } = event.currentTarget.dataset;
      const nextCoupons = this.data.coupons.map(item =>
        item.id === id ? Object.assign({}, item, { expanded: !item.expanded }) : item
      );
      this.setData({ coupons: nextCoupons });
    },
    copyCouponNo(event) {
      const { no } = event.currentTarget.dataset;
      if (!no) return;
      wx.setClipboardData({
        data: String(no),
        success: () => wx.showToast({ title: '券号已复制', icon: 'none' })
      });
    },
    handleUse() {
      wx.switchTab({ url: '/pages/menu/menu' });
    },
    handleExchange() {
      wx.showToast({ title: '兑换优惠券暂未接入', icon: 'none' });
    },
    handleViewStores(event) {
      const { couponId } = event.currentTarget.dataset;
      wx.navigateTo({ url: `/pages/coupon-stores/coupon-stores?couponId=${couponId}` });
    },
    handleViewProducts(event) {
      const { couponId } = event.currentTarget.dataset;
      wx.navigateTo({ url: `/pages/coupon-stores/coupon-stores?couponId=${couponId}&next=products` });
    }
  })
);
