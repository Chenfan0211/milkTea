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
    loading: { type: Boolean, value: false },
    delta: { type: Number, value: 1 }
  },
  data: {
    statusBarHeight: 20,
    navBarHeight: 44
  },
  lifetimes: {
    attached() {
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
      const rect = wx.getMenuButtonBoundingClientRect();
      const statusBarHeight = windowInfo.statusBarHeight || 0;
      const navBarHeight = rect && rect.height ? Math.max(rect.height + (rect.top - statusBarHeight) * 2, 44) : 44;
      this.setData({ statusBarHeight, navBarHeight });
    }
  },
  methods: {
    back() {
      if (this.data.delta) wx.navigateBack({ delta: this.data.delta });
      this.triggerEvent('back', { delta: this.data.delta });
    }
  }
});
