const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { pointsRecords } = require('../../data/mock');

Page(
  withShare({
    data: {
      pointsRecords: []
    },
    onShow() {
      // 先用本地兜底，再拉后端流水
      this.setData({ pointsRecords: (getApp().globalData.pointsRecords || pointsRecords || []) });
      api
        .fetchPointsRecords()
        .then(list => {
          if (Array.isArray(list) && list.length) {
            this.setData({ pointsRecords: list });
          }
        })
        .catch(() => null);
    }
  })
);
