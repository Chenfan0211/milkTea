const { withShare } = require('../../utils/share');
const entry = require('../../utils/entry-login');
const navigate = require('../../utils/navigate');
const referrer = require('../../utils/referrer');

/**
 * 启动页：所有冷启动入口（扫码 / 分享卡片 / 朋友圈 / 图标）的统一收口。
 *
 * 流程：
 *   1) 静默登录（带超时兜底）；
 *   2) 公开入口始终进入还原后的目标页；
 *   3) 未注册、登录失败、超时、未绑手机号均不强制跳授权页；
 *   4) 下单、订单、资料等受保护操作继续由 login-guard 按需拦截。
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
      // 邀请分享进入：先落盘邀请人，供后续注册绑定推荐关系。
      // 越早捕获越好 —— 用户可以拒绝授权、中途退出，之后再进就再也拿不到这个参数了。
      referrer.captureFromOptions(options);
      this.bootstrap();
    },
    bootstrap() {
      const options = this.entryOptions || {};
      const target = entry.resolveEntryTarget(options);
      entry
        .ensureEntryLogin()
        .then(() => this.goTarget(target))
        .catch(() => this.goTarget(target));
    },
    goTarget(target) {
      navigate.go(target || entry.HOME_PATH, {
        // 目标页非法或栈异常时回落首页，避免停在启动页
        fail: () => navigate.go(entry.HOME_PATH)
      });
    }
  })
);
