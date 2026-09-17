const { withShare } = require('../../utils/share')
const { pointsRecords } = require('../../data/mock')

Page(withShare({
  data: {
    pointsRecords: []
  },
  onShow() {
    this.setData({ pointsRecords: getApp().globalData.pointsRecords })
  }
}))
