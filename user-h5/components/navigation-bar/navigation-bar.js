/**
 * 计算右侧插槽需要为原生胶囊按钮让出的宽度（px）。
 *
 * 胶囊按钮由微信固定悬浮在窗口右上角，slot="right" 的内容若沿用 right: 0
 * 会被胶囊遮挡（例如页面头像），必须整体左移到胶囊左侧；页面自身的
 * margin-right 则作为内容与胶囊之间的视觉间距。
 */
function resolveRightInset(windowInfo, menuRect) {
  const windowWidth = windowInfo.windowWidth || windowInfo.screenWidth || 0;
  if (!windowWidth || !menuRect || !menuRect.left) return 0;
  return Math.max(windowWidth - menuRect.left, 0);
}

/**
 * 内容行与胶囊按钮底边之间的间距（rpx）。
 */
const CAPSULE_GAP_RPX = 4;

/**
 * 把 rpx 间距换算成 wx 布局使用的 px。
 */
function capsuleGapPx(windowInfo) {
  const windowWidth = windowInfo.windowWidth || windowInfo.screenWidth || 375;
  return (CAPSULE_GAP_RPX * windowWidth) / 750;
}

Component({
  // styleIsolation 只在 js 声明（与 product-card 一致）：
  // · 不得与 json 重复声明，否则 Skyline 按需注入下会触发框架解析崩溃；
  // · 必须是 apply-shared，否则 slot 插槽内容（如订单页「开发票」）样式会被隔离而不可见。
  options: { multipleSlots: true, styleIsolation: 'apply-shared' },
  properties: {
    extClass: { type: String, value: '' },
    title: { type: String, value: '' },
    variant: { type: String, value: 'solid' },
    background: { type: String, value: '#FFFFFF' },
    color: { type: String, value: '#2F302D' },
    back: { type: Boolean, value: false },
    // true 时把标题与插槽内容整体下移到原生胶囊按钮下方，避免右上角互相遮挡
    belowCapsule: { type: Boolean, value: false },
    loading: { type: Boolean, value: false },
    delta: { type: Number, value: 1 }
  },
  data: {
    statusBarHeight: 20,
    navBarHeight: 44,
    contentTop: 20,
    rightInset: 0
  },
  lifetimes: {
    attached() {
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      const rect = wx.getMenuButtonBoundingClientRect();
      const statusBarHeight = windowInfo.statusBarHeight || 0;
      const hasCapsule = !!(rect && rect.height);
      const belowCapsule = !!this.data.belowCapsule && hasCapsule;
      const navBarHeight = hasCapsule ? Math.max(rect.height + (rect.top - statusBarHeight) * 2, 44) : 44;
      this.setData({
        statusBarHeight,
        navBarHeight,
        contentTop: belowCapsule ? rect.bottom + capsuleGapPx(windowInfo) : statusBarHeight,
        // 内容行已让开胶囊按钮时，右侧插槽不再需要额外内缩
        rightInset: belowCapsule ? 0 : resolveRightInset(windowInfo, rect)
      });
    }
  },
  methods: {
    back() {
      if (this.data.delta) wx.navigateBack({ delta: this.data.delta });
      this.triggerEvent('back', { delta: this.data.delta });
    }
  }
});
