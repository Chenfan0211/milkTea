const auth = require('../../utils/auth');
const guard = require('../../utils/login-guard');
const api = require('../../utils/api');

/**
 * 登录弹层（首页「点击登录」触发）。
 *
 * 与 pages/auth-login 的分工：
 *   · 本组件用于**页内唤起**（首页昵称位、需要登录的操作），不跳页、体验更轻；
 *   · pages/auth-login 作为受保护操作触发的整页兜底。
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
      // 提前刷新微信会话，用户真正点击手机号按钮时可直接使用最新 session_key。
      this.preparePhoneAuthorization().catch(() => {});
    }
  },
  methods: {
    preparePhoneAuthorization() {
      this._phoneAuthorizationReady = false;
      this._phoneAuthorizationFailed = false;
      this._phoneAuthorizationPromise = auth
        .preparePhoneAuthorization()
        .then(result => {
          this._preparedPhoneAuthorization = result || {
            needRegister: false,
            registerContext: null
          };
          this._phoneAuthorizationReady = true;
          return this._preparedPhoneAuthorization;
        })
        .catch(error => {
          this._phoneAuthorizationFailed = true;
          this._phoneAuthorizationError = error;
          this._phoneAuthorizationPromise = null;
          throw error;
        });
      return this._phoneAuthorizationPromise;
    },
    resetPhoneAuthorization() {
      this._phoneAuthorizationReady = false;
      this._phoneAuthorizationFailed = false;
      this._phoneAuthorizationError = null;
      this._preparedPhoneAuthorization = null;
      this._phoneAuthorizationPromise = null;
    },
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
      if (!this._phoneAuthorizationReady) {
        guard.toast(
          this._phoneAuthorizationFailed
            ? '登录会话准备失败，请稍后重试'
            : '正在准备登录，请稍后重试'
        );
        if (this._phoneAuthorizationFailed) {
          this.preparePhoneAuthorization().catch(() => {});
        }
        return;
      }
      const prepared = this._preparedPhoneAuthorization || {};
      const registerContext = prepared.registerContext || null;
      const needRegister = Boolean(
        prepared.needRegister && registerContext && registerContext.registerToken
      );
      const promise = needRegister
        ? auth.registerByPhone(registerContext.registerToken, detail.encryptedData, detail.iv)
        : api.bindPhone(detail.encryptedData, detail.iv);
      return promise
        .then(data => {
          this.triggerEvent('success', { needRegister, data });
        })
        .catch(error => {
          if (auth.isSessionInvalidError(error)) {
            // encryptedData/iv 已绑定旧 session_key，必须丢弃并要求用户重新点击授权。
            auth.clearSession();
            auth.clearRegisterContext();
            this.resetPhoneAuthorization();
            this.preparePhoneAuthorization().catch(() => {});
            guard.toast('授权会话已刷新，请再次点击同意');
            return;
          }
          this.triggerEvent('fail', { needRegister, error });
          guard.toast((error && error.message) || '授权失败，请重试');
        });
    }
  }
});
