const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const tabBarConfig = require('../../utils/tab-bar-config');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta, refreshMemberLevelsFromRemote } = require('../../utils/member-level');

Page(
  withShare({
    data: {
      navigationTitle: '会员权益',
      showBack: true,
      currentLevel: 'Lv1',
      currentIndex: 0,
      currentGrowth: 0,
      progressPercent: 0,
      progressLabel: '',
      levels: [],
      axis: []
    },
    onLoad() {
      return tabBarConfig.ready(api.fetchConfig).then(() => {
        if (!tabBarConfig.isMemberZoneTab()) return;
        wx.switchTab({ url: tabBarConfig.getThirdTabPath() });
      });
    },
    onShow() {
      if (tabBarConfig.isMemberZoneTab() && this.getTabBar) {
        this.getTabBar().setData({ selected: 2 });
      }
      // 等级数据来自后台，拉取完成后再渲染
      return refreshMemberLevelsFromRemote().then(() => this.syncMember());
    },
    syncMember() {
      const meta = buildLevelMeta(getUserProfile());
      this.setData({
        currentLevel: meta.currentLevel,
        currentIndex: meta.currentIndex,
        currentGrowth: meta.currentGrowth,
        progressPercent: meta.progressPercent,
        progressLabel: meta.progressLabel,
        levels: meta.levels,
        axis: meta.axis
      });
    },
    openLevelRules() {
      wx.navigateTo({ url: '/pages/member-level-rules/member-level-rules' });
    }
  })
);
