const { withShare } = require('../../utils/share')
Page(withShare({
  onShow() {
    if (this.getTabBar) this.getTabBar().setData({ selected: 2 })
  }
}))
