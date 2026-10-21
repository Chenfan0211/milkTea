const { withShare } = require('../../utils/share');
const loginGuard = require('../../utils/login-guard');
const { refreshCouponsFromRemote } = require('../../utils/coupons');

/**
 * 券列表 Tab：文案 + 后端四态口径。
 *
 * 后端 myCoupons 统一推导四态（CouponService#deriveTab），一张券只落一个 Tab：
 *   未使用 = UNUSED 已生效未过期 + LOCKED 订单占用中（占用中标注「订单占用中」且不可用）
 *   待生效 = UNUSED 但生效时间未到
 *   已过期 = EXPIRED 或「UNUSED 但已过有效期」（DAYS 类型券库里没有过期落点，靠现算）
 *   已使用 = USED（终态优先，之后过了有效期也不改归类）
 *
 * 「能不能用」看后端的 usable；「是不是过期」看后端的 expired ——
 * 两者不能互相推断（已使用券同样是 usable=false）。
 */
const COUPON_TABS = [
  { value: 'UNUSED', label: '未使用' },
  { value: 'USED', label: '已使用' },
  { value: 'EXPIRED', label: '已过期' },
  { value: 'PENDING', label: '待生效' }
];

const USABLE_TABS = ['UNUSED', 'PENDING'];

/** 终态 Tab：到期时间已经过去是常态，文案必须说清「已于 xx 过期」。 */
const TERMINAL_TABS = ['USED', 'EXPIRED'];

/** 订单占用中（LOCKED）：券仍显示在「未使用」Tab，但不可用。 */
const LOCKED_TEXT = '订单占用中';

/**
 * 已使用 / 已过期券的到期文案。
 *
 * 已使用券之后过了有效期是正常的（终态优先），但卡片若仍写「xx 到期」，
 * 看起来就像「都过期了却被分到已使用」，所以这里显式写成「已于 xx 过期」。
 */
function pastExpiryText(coupon) {
  const raw = coupon && coupon.expireAt ? String(coupon.expireAt) : '';
  if (!raw) return '';
  const ms = Date.parse(raw.replace(' ', 'T'));
  if (!Number.isFinite(ms) || ms >= Date.now()) return '';
  return `已于 ${raw.replace('T', ' ').slice(0, 16)} 过期`;
}

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
      // 我的优惠券需登录：未登录时先引导登录，登录后再拉取。
      loginGuard.requireLogin(() => this.loadCoupons(this.data.activeTab), { reason: '登录后可查看我的优惠券' });
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
      // 未使用 / 待生效属于「可操作区」；能否点「去使用」还要看券本身
      //（订单占用中、待生效的券后端返回 usable=false，不能下单）
      const tabUsable = USABLE_TABS.indexOf(status) !== -1;
      const usable = tabUsable && coupon.usable !== false;
      // 已使用 / 已过期卡片：把已经过去的到期时间说清楚，避免看起来像「还在有效期」
      const overdueText = TERMINAL_TABS.indexOf(status) !== -1 ? pastExpiryText(coupon) : '';
      const statusLabel = COUPON_TABS.filter(item => item.value === status).map(item => item.label)[0];
      return Object.assign({}, coupon, {
        expanded: false,
        usable,
        showCount: tabUsable,
        disabled: !tabUsable,
        expiryText: overdueText || coupon.expiryText,
        statusText: coupon.locked && status === 'UNUSED' ? LOCKED_TEXT : statusLabel || ''
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
