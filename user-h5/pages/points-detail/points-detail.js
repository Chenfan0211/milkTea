const { pointsRecords } = require('../../data/mock')

Page({
  data: {
    pointsRecords: []
  },
  onShow() {
    this.setData({ pointsRecords: getApp().globalData.pointsRecords })
  }
})
