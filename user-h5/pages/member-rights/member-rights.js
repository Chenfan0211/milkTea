const { withShare } = require('../../utils/share');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta } = require('../../utils/member-level');

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
      this.syncMember();
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
