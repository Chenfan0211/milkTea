const { withShare } = require('../../utils/share');
const api = require('../../utils/api');

Page(
  withShare({
    data: {
      pointsRecords: []
    },
    onShow() {
      // 时光币流水以后端为准（未登录时列表为空）
      this.setData({ pointsRecords: getApp().globalData.pointsRecords || [] });
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
