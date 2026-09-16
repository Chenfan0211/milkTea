Component({
  options: { multipleSlots: true, styleIsolation: 'apply-shared' },
  properties: {
    extClass: { type: String, value: '' },
    title: { type: String, value: '' },
    variant: { type: String, value: 'solid' },
    background: { type: String, value: '#FFFFFF' },
    color: { type: String, value: '#2F302D' },
    back: { type: Boolean, value: false },
    search: { type: Boolean, value: false },
    loading: { type: Boolean, value: false },
    delta: { type: Number, value: 1 }
  },
  data: {
    statusBarHeight: 20,
    navBarHeight: 44
  },
  lifetimes: {
    attached() {
      const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
      const rect = wx.getMenuButtonBoundingClientRect()
      const statusBarHeight = windowInfo.statusBarHeight || 0
      const navBarHeight = rect && rect.height
        ? Math.max(rect.height + (rect.top - statusBarHeight) * 2, 44)
        : 44
      this.setData({ statusBarHeight, navBarHeight })
    }
  },
  methods: {
    back() {
      if (this.data.delta) wx.navigateBack({ delta: this.data.delta })
      this.triggerEvent('back', { delta: this.data.delta })
    },
    handleSearch() {
      this.triggerEvent('search')
    }
  }
})
