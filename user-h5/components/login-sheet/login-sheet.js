const auth = require('../../utils/auth');
const guard = require('../../utils/login-guard');
const api = require('../../utils/api');

/**
 * 登录弹层（首页「点击登录」触发）。
 *
 * 与 pages/auth-login 的分工：
 *   · 本组件用于**页内唤起**（首页昵称位、需要登录的操作），不跳页、体验更轻；
 *   · pages/auth-login 作为整页兜底（冷启动未注册、分享/扫码进入）。
 *
 * 合规：getPhoneNumber 必须由用户点击触发，本组件只承载按钮，绝不程序化调起。
 *
 * 渲染约定（Skyline 踩坑）：本组件由**页面侧 `wx:if` 控制是否渲染**，
 * 组件自身不再用 `visible` 做显隐。原因：Skyline 的 glass-easel 会为自定义组件
 * 宿主节点保留占位，组件内根的 `position: fixed` 全屏层会拦住整页点击
 * （表现为页面所有按钮点了没反应）。因此这里不接收 visible，也不在内部隐藏。
 */
Component({
  properties: {
    brandName: { type: String, value: '五零时光' }
  },
  data: {
    agreementChecked: false
  },
  lifetimes: {
    attached() {
      // 每次挂载都是全新实例，勾选态天然重置；这里显式声明便于阅读
      this.setData({ agreementChecked: false });
    }
  },
  methods: {
    toggleAgreement() {
      this.setData({ agreementChecked: !this.data.agreementChecked });
    },
    handleClose() {
      this.triggerEvent('close');
    },
    handleSkip() {
      if (!this.data.agreementChecked) {
        guard.toast('请先阅读并同意协议');
        return;
      }
      this.triggerEvent('skip');
    },
    openAgreement(event) {
      const type = (event.currentTarget.dataset && event.currentTarget.dataset.type) || 'agreement';
      wx.navigateTo({ url: `/pages/legal/legal?type=${type}` });
    },
    /**
     * 微信手机号授权。
     * 未注册 -> 走注册并登录；已登录未绑手机号 -> 走绑定手机号。
     * 成功/失败都通过事件回传父页面，由父页面决定后续路由。
     */
    handleGetPhoneNumber(event) {
      const detail = event.detail || {};
      if (!detail.encryptedData || !detail.iv) {
        guard.toast('已取消授权');
        return;
      }
      if (!this.data.agreementChecked) {
        guard.toast('请先阅读并同意协议');
        return;
      }
      const registerContext = auth.getRegisterContext();
      const needRegister = Boolean(registerContext && registerContext.registerToken && !auth.isLoggedIn());
      const promise = needRegister
        ? auth.registerByPhone(registerContext.registerToken, detail.encryptedData, detail.iv)
        : api.bindPhone(detail.encryptedData, detail.iv);
      promise
        .then(data => {
          this.triggerEvent('success', { needRegister, data });
        })
        .catch(() => {
          this.triggerEvent('fail', { needRegister });
        });
    }
  }
});