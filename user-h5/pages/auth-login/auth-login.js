const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const guard = require('../../utils/login-guard');
const auth = require('../../utils/auth');
const navigate = require('../../utils/navigate');

/**
 * 授权页（温馨提示 + 协议确认）。
 *
 * 交互约定（重要）：
 *   底部「同意」按钮本身带 open-type="getPhoneNumber"，点击即「同意协议 + 授权手机号」，
 *   一次点击完成「注册建号 -> 建立登录态 -> 进入目标页」，不再要求用户额外点第二次按钮。
 *   未勾选协议时按钮置灰并拦截（授权必须建立在用户知情同意之上）。
 *
 * 来源：
 *   1) 受保护操作：由 login-guard 按需跳转进来；
 *   2) 已登录未绑手机号：由业务操作触发绑定引导。
 *
 * 跳转注意：落点常是 tabBar 页（首页），必须走 utils/navigate，
 * 否则 reLaunch 到 tabBar 页会静默失败、页面停在授权页。
 */
Page(
  withShare({
    data: {
      // 入口引导态（启动页进入）：完成后落到 entryTarget
      entryMode: false,
      // 未注册态：走「注册并登录」而非「绑定手机号」
      needRegister: false,
      agreementChecked: false,
      entryTip: ''
    },
    onLoad(options) {
      const opts = options || {};
      // 入口引导态：来自启动页，完成后落到 entryTarget
      this.entryMode = (opts.mode || '') === 'entry' || Boolean(opts.target);
      // 落点；来源不合法时回落首页，避免被构造参数跳转
      this.entryTarget = this.entryMode && opts.target ? decodeURIComponent(opts.target) : '/pages/home/home';
      // 标记本次授权是否已完成，供 onUnload 判断是否清理待执行动作
      this.completed = false;
      // 未注册态：静默登录拿到的是一次性注册凭证而非 token，
      // 本次授权走「注册并登录」而非「绑定手机号」。
      const registerContext = auth.getRegisterContext();
      this.needRegister = Boolean(registerContext && registerContext.registerToken && !auth.isLoggedIn());
      this.registerToken = this.needRegister ? registerContext.registerToken : '';
      this._phoneAuthorizationReady = false;
      this._phoneAuthorizationFailed = false;
      this.setData({
        entryMode: this.entryMode,
        needRegister: this.needRegister,
        entryTip: this.needRegister
          ? '同意后即可完成注册并开始使用'
          : '同意后可下单、领券并同步会员权益'
      });
      // 提前换一次最新 session_key；用户点击按钮时只允许使用这次刷新的结果。
      this.preparePhoneAuthorization().catch(() => {});
    },
    onUnload() {
      // 用户中途返回且未完成授权时，清掉待执行动作，避免脏动作残留
      if (!this.completed) guard.clearPendingAction();
    },
    toggleAgreement() {
      this.setData({ agreementChecked: !this.data.agreementChecked });
    },
    preparePhoneAuthorization() {
      this._phoneAuthorizationReady = false;
      this._phoneAuthorizationFailed = false;
      this._phoneAuthorizationPromise = auth
        .preparePhoneAuthorization()
        .then(prepared => {
          this.applyPreparedAuthorization(prepared);
          return prepared;
        })
        .catch(error => {
          this._phoneAuthorizationFailed = true;
          this._phoneAuthorizationError = error;
          this._phoneAuthorizationPromise = null;
          throw error;
        });
      return this._phoneAuthorizationPromise;
    },
    applyPreparedAuthorization(prepared) {
      const registerContext = (prepared && prepared.registerContext) || null;
      this.needRegister = Boolean(
        prepared && prepared.needRegister && registerContext && registerContext.registerToken
      );
      this.registerToken = this.needRegister ? registerContext.registerToken : '';
      this._preparedPhoneAuthorization = prepared || {
        needRegister: false,
        registerContext: null
      };
      this._phoneAuthorizationReady = true;
      this.setData({
        needRegister: this.needRegister,
        entryTip: this.needRegister
          ? '同意后即可完成注册并开始使用'
          : '同意后可下单、领券并同步会员权益'
      });
    },
    resetPhoneAuthorization() {
      this._phoneAuthorizationReady = false;
      this._phoneAuthorizationFailed = false;
      this._phoneAuthorizationError = null;
      this._preparedPhoneAuthorization = null;
      this._phoneAuthorizationPromise = null;
    },

    /**
     * 点击「同意」：协议确认 + 微信手机号授权 + 注册登录，一步到位。
     *
     * 必须由 open-type="getPhoneNumber" 的按钮触发（微信硬约束，不可程序化调起），
     * 因此这里同时承担「校验勾选」与「处理授权结果」两个职责。
     */
    handleAgree(e) {
      // 未勾选协议：不继续授权，避免「未同意先采集」
      if (!this.data.agreementChecked) {
        guard.toast('请先阅读并勾选同意协议');
        return;
      }
      const detail = (e && e.detail) || {};
      if (!detail.encryptedData || !detail.iv) {
        // 用户在系统弹窗点了「拒绝」：留在本页，允许重试或仅浏览
        guard.toast('已取消授权，可重新点击同意或选择仅浏览');
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
      const promise = this.needRegister
        ? auth.registerByPhone(this.registerToken, detail.encryptedData, detail.iv)
        : api.bindPhone(detail.encryptedData, detail.iv);
      return promise
        .then(() => this.afterBound())
        .catch(error => {
          if (auth.isSessionInvalidError(error)) {
            auth.clearSession();
            auth.clearRegisterContext();
            this.resetPhoneAuthorization();
            this.preparePhoneAuthorization().catch(() => {});
            guard.toast('授权会话已刷新，请再次点击同意');
            return;
          }
          guard.toast((error && error.message) || '授权失败，请重试');
        });
    },

    // ---------- 授权完成：建立登录态并进入目标页 ----------
    afterBound() {
      this.completed = true;
      guard.toast('登录成功');
      const target = this.entryTarget || '/pages/home/home';
      if (this.data.entryMode) {
        // 入口态：目标可能是 tabBar 页（首页），必须走 navigate 自动选 switchTab
        navigate.go(target, {
          fail: () => navigate.go('/pages/home/home')
        });
        guard.flushPendingAction();
        return;
      }
      // 非入口态：先返回上一级，返回完成后再续跑原操作
      // （避免在授权页上下文执行业务动作，页面已卸载导致 setData 失效）。
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages && pages.length > 1) {
        wx.navigateBack({
          delta: 1,
          complete() {
            guard.flushPendingAction();
          }
        });
        return;
      }
      navigate.go('/pages/home/home', {
        complete() {
          guard.flushPendingAction();
        }
      });
    },

    // ---------- 拒绝仅浏览：放行公开内容（交易仍会拦截） ----------
    handleReject() {
      this.completed = true;
      guard.clearPendingAction();
      navigate.go('/pages/home/home', {
        fail: () => navigate.go('/pages/home/home')
      });
    },

    // 协议独立页面，按类型跳转
    openAgreement(e) {
      const type = (e.currentTarget.dataset && e.currentTarget.dataset.type) || 'agreement';
      wx.navigateTo({ url: `/pages/legal/legal?type=${type}` });
    }
  })
);
