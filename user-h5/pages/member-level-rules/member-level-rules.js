const { withShare } = require('../../utils/share');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta, getMemberLevels, refreshMemberLevelsFromRemote } = require('../../utils/member-level');

Page(
  withShare({
    data: {
      levels: [],
      currentIndex: 0
    },
    onShow() {
      // 等级说明由后台配置（member_level 表）
      refreshMemberLevelsFromRemote().then(() => {
        const meta = buildLevelMeta(getUserProfile());
        this.setData({
          levels: meta.levels,
          currentIndex: meta.currentIndex
        });
      });
    }
  })
);
