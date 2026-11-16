const api = require('../../utils/api');
const tabBarConfig = require('../../utils/tab-bar-config');

Page({
  onLoad() {
    tabBarConfig.ready(api.fetchConfig).then(() => {
      wx.switchTab({ url: tabBarConfig.getThirdTabPath() });
    });
  }
});
