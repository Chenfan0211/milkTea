const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta, refreshMemberLevelsFromRemote } = require('../../utils/member-level');

Page(
  withShare({
    data: {
      currentLevel: 'Lv1',
      currentIndex: 0,
      currentGrowth: 0,
      progressPercent: 0,
      progressLabel: '',
      levels: [],
      axis: []
    },
    onShow() {
      if (this.getTabBar) this.getTabBar().setData({ selected: 2 });
      // 会员等级由后台配置，先拉取再渲染
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

