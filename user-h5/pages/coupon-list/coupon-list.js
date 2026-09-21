const { withShare } = require('../../utils/share');
const { getCoupons } = require('../../utils/coupons');

function countCoupons(list) {
  return list.reduce((sum, item) => sum + item.quantity, 0);
}

Page(
  withShare({
    data: {
      coupons: [],
      couponCount: 0
    },
    onShow() {
      const list = getCoupons();
      this.setData({ coupons: list, couponCount: countCoupons(list) });
    },
    handleSubscribe() {
      wx.showToast({ title: '订阅功能暂未接入', icon: 'none' });
    },
    showHelp() {
      wx.showToast({ title: '优惠券提醒说明暂未接入', icon: 'none' });
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
      const { id } = event.currentTarget.dataset;
      wx.navigateTo({ url: `/pages/coupon-stores/coupon-stores?couponId=${id}` });
    },
    handleViewProducts(event) {
      const { id } = event.currentTarget.dataset;
      wx.navigateTo({ url: `/pages/coupon-stores/coupon-stores?couponId=${id}&next=products` });
    },
    handleHistory() {
      wx.showToast({ title: '历史优惠券暂未接入', icon: 'none' });
    },
    handleBatchGift() {
      wx.showToast({ title: '批量赠送暂未接入', icon: 'none' });
    },
    handleGiftRecord() {
      wx.showToast({ title: '赠送记录暂未接入', icon: 'none' });
    }
  })
);
