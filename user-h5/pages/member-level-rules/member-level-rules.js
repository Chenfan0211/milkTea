const { withShare } = require('../../utils/share');
const { memberLevels } = require('../../data/mock');
const { getUserProfile } = require('../../utils/user-profile');
const { buildLevelMeta } = require('../../utils/member-level');

const initialMeta = buildLevelMeta(getUserProfile());

Page(
  withShare({
    data: {
      levels: memberLevels.map(item =>
        Object.assign({}, item, {
          benefits: item.benefits.map(benefit => Object.assign({}, benefit))
        })
      ),
      currentIndex: initialMeta.currentIndex
    },
    onShow() {
      const meta = buildLevelMeta(getUserProfile());
      this.setData({ currentIndex: meta.currentIndex });
    }
  })
);
