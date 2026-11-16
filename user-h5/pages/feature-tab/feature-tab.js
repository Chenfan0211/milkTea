const api = require('../../utils/api');
const tabBarConfig = require('../../utils/tab-bar-config');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta, refreshMemberLevelsFromRemote } = require('../../utils/member-level');
const { buildShareAppMessage, buildShareTimeline } = require('../../utils/share');

const ROUTE = 'pages/feature-tab/feature-tab';

function shareTitle() {
  return tabBarConfig.isMemberZoneTab() ? '五零时光会员专区' : '五零时光舌象体质检测';
}

Page({
  data: {
    ready: false,
    memberZoneMode: false,
    appVersion: '',
    currentLevel: 'Lv1',
    currentIndex: 0,
    currentGrowth: 0,
    progressPercent: 0,
    progressLabel: '',
    levels: [],
    axis: []
  },

  onLoad(options = {}) {
    this.shareOptions = options || {};
    if (typeof wx !== 'undefined' && wx.showShareMenu) {
      try {
        wx.showShareMenu({ menus: ['shareAppMessage', 'shareTimeline'] });
      } catch (error) {
        // 不同基础库对分享菜单能力支持不一致，不影响页面加载。
      }
    }
    return this.initialize();
  },

  onShow() {
    if (this.getTabBar) {
      this.getTabBar().setData({
        selected: tabBarConfig.getTabBarSelectedIndex('/pages/feature-tab/feature-tab')
      });
    }
    if (this.data.ready && this.data.memberZoneMode) {
      return this.syncMember();
    }
    return undefined;
  },

  initialize() {
    return tabBarConfig.ready(api.fetchConfig).then(() => {
      const memberZoneMode = tabBarConfig.isMemberZoneTab();
      this.setData({
        ready: true,
        memberZoneMode,
        appVersion: tabBarConfig.getCurrent().appVersion
      });
      if (memberZoneMode) return this.syncMember();
      return null;
    });
  },

  syncMember() {
    return refreshMemberLevelsFromRemote().then(() => {
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
    });
  },

  openLevelRules() {
    wx.navigateTo({ url: '/pages/member-level-rules/member-level-rules' });
  },

  onShareAppMessage() {
    return buildShareAppMessage(ROUTE, this.shareOptions, { title: shareTitle() });
  },

  onShareTimeline() {
    return buildShareTimeline(ROUTE, this.shareOptions, { title: shareTitle() });
  }
});
