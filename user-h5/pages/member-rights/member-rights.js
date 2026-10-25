const { withShare } = require('../../utils/share');
const featurePolicy = require('../../utils/feature-policy');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta, refreshMemberLevelsFromRemote } = require('../../utils/member-level');

const MEMBER_ZONE_TAB = featurePolicy.isMemberZoneTab();

Page(
  withShare({
    data: {
      navigationTitle: MEMBER_ZONE_TAB ? '会员专区' : '会员权益',
      showBack: !MEMBER_ZONE_TAB,
      currentLevel: 'Lv1',
      currentIndex: 0,
      currentGrowth: 0,
      progressPercent: 0,
      progressLabel: '',
      levels: [],
      axis: []
    },
    onShow() {
      if (MEMBER_ZONE_TAB && this.getTabBar) this.getTabBar().setData({ selected: 2 });
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
