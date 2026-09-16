Page({
  onShow() {
    if (this.getTabBar) this.getTabBar().setData({ selected: 2 })
  }
})
