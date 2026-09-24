const { withShare } = require('../../utils/share');
const entry = require('../../utils/entry-login');

/**
 * 启动页：所有冷启动入口（扫码 / 分享卡片 / 朋友圈 / 图标）的统一收口。
 *
 * 流程：
 *   1) 静默登录（带超时兜底，失败也放行）；
 *   2) 需要引导绑手机号 -> reLaunch 到授权页（带 mode=entry 与来源参数）；
 *   3) 否则 reLaunch 到还原后的目标页。
 *
 * 全程只做路由编排，不做业务；任何异常都必须能落到目标页，绝不卡死。
 * 合规约束：本页绝不程序化调起 getPhoneNumber，只做跳转编排。
 *
 * 说明：本页已登记为私密页（utils/share.js 的 PRIVATE_PAGES），
 * 分享一律回落首页，但按项目约定仍统一套 withShare 包装器。
 */
Page(
  withShare({
    onLoad(options) {
      this.entryOptions = options || {};
      this.route();
    },
    route() {
      const options = this.entryOptions || {};
      entry.ensureEntryLogin().then(result => {
        const state = result && result.state ? result.state : { level: 'anonymous' };
        const target = entry.resolveEntryTarget(options);
        // 引导前置条件：登录必须真的成功。登录失败 / 超时说明连 token 都没拿到，
        // 此时跳授权页只会让用户点完手机号仍然绑不上（后端仍判未登录），
        // 因此直接放行到目标页，由页面各自的登录失败提示条兜底重试。
        const loggedIn = Boolean(result && result.ok && state.hasToken);
        const needPrompt = loggedIn && state.level !== 'full' && entry.shouldPromptEntry();
        if (!needPrompt) {
          this.goTarget(target);
          return;
        }
        entry.markEntryPrompted();
        const params = ['mode=entry', `target=${encodeURIComponent(target)}`];
        if (options.from) params.push(`from=${encodeURIComponent(String(options.from))}`);
        if (options.query) params.push(`query=${encodeURIComponent(String(options.query))}`);
        wx.reLaunch({
          url: `/pages/auth-login/auth-login?${params.join('&')}`,
          // 引导页跳转失败时不能停在启动页
          fail: () => this.goTarget(target)
        });
      });
    },
    goTarget(target) {
      wx.reLaunch({
        url: target || entry.HOME_PATH,
        fail() {
          // 目标页非法或栈异常时回落首页，避免停在启动页
          wx.reLaunch({ url: entry.HOME_PATH });
        }
      });
    }
  })
);

