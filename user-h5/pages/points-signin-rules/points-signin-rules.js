const { withShare } = require('../../utils/share');
const api = require('../../utils/api');

Page(
  withShare({
    data: {
      signInRules: []
    },
    onLoad() {
      // 签到规则由后台配置（app_config.signin_rules）
      api
        .fetchSigninConfig()
        .then(cfg => {
          if (cfg && Array.isArray(cfg.rules) && cfg.rules.length) {
            this.setData({ signInRules: cfg.rules });
          }
        })
        .catch(() => null);
    }
  })
);
