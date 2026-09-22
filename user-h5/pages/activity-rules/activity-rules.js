const { withShare } = require('../../utils/share');
const api = require('../../utils/api');

Page(
  withShare({
    data: {
      menuActivity: {}
    },
    onLoad() {
      // 活动说明由后台配置（app_config.menu_activity）
      api
        .fetchHomeConfig()
        .then(cfg => {
          if (cfg && cfg.menuActivity) this.setData({ menuActivity: cfg.menuActivity });
        })
        .catch(() => null);
    }
  })
);
