const { withShare } = require('../../utils/share');
const entry = require('../../utils/entry-login');
const navigate = require('../../utils/navigate');

/**
 * 启动页：所有冷启动入口（扫码 / 分享卡片 / 朋友圈 / 图标）的统一收口。
 *
 * 流程：
 *   1) 静默登录（带超时兜底）；
 *   2) 未注册 -> 跳到授权页显示「温馨提示」协议确认（可「拒绝仅浏览」）；
 *   3) 登录失败 / 超时 -> 同样进授权页重试（不允许未登录浏览）；
 *   4) 已登录未绑手机号 -> 按需引导绑定（带 mode=entry 与来源参数）；
 *   5) 否则落到还原后的目标页。
 *
 * 全程只做路由编排，不做业务；任何异常都必须能落到目标页，绝不卡死。
 * 合规约束：本页绝不程序化调起 getPhoneNumber，只做跳转编排。
 *
 * 跳转注意：目标可能是 tabBar 页（首页），必须走 utils/navigate 自动选
 * switchTab，否则 reLaunch 到 tabBar 页会静默失败、页面停在启动页。
 *
 * 说明：本页已登记为私密页（utils/share.js 的 PRIVATE_PAGES），
 * 分享一律回落首页，但按项目约定仍统一套 withShare 包装器。
 */
Page(
  withShare({
    onLoad(options) {
      this.entryOptions = options || {};
      this.bootstrap();
    },
    bootstrap() {
      const options = this.entryOptions || {};
      entry.ensureEntryLogin().then(result => {
        const state = result && result.state ? result.state : { level: 'anonymous' };
        const target = entry.resolveEntryTarget(options);
        // 未注册：后端查无 openid，只下发一次性注册凭证，默认未登录。
        // 跳授权页展示协议确认；「拒绝仅浏览」仅放行公开内容，交易仍需登录。
        if (result && result.needsRegister) {
          this.goAuthPage(target, options);
          return;
        }
        // 已登录：拉取资料成功后才放行；未绑手机号时按需引导绑定。
        const loggedIn = Boolean(result && result.ok && state.hasToken);
        if (!loggedIn) {
          // 登录失败 / 超时：同样进授权页，用户在可在此重试。
          this.goAuthPage(target, options);
          return;
        }
        const needPrompt = state.level !== 'full' && entry.shouldPromptEntry();
        if (!needPrompt) {
          this.goTarget(target);
          return;
        }
        entry.markEntryPrompted();
        this.goAuthPage(target, options);
      });
    },
    goAuthPage(target, options) {
      const params = [`target=${encodeURIComponent(target)}`];
      if (options && options.from) params.push(`from=${encodeURIComponent(String(options.from))}`);
      if (options && options.query) params.push(`query=${encodeURIComponent(String(options.query))}`);
      wx.redirectTo({
        url: `/pages/auth-login/auth-login?${params.join('&')}`,
        // 授权页打开失败时不能停在启动页
        fail: () => this.goTarget(target)
      });
    },
    goTarget(target) {
      navigate.go(target || entry.HOME_PATH, {
        // 目标页非法或栈异常时回落首页，避免停在启动页
        fail: () => navigate.go(entry.HOME_PATH)
      });
    }
  })
);