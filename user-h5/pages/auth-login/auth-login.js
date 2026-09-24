const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const guard = require('../../utils/login-guard');
const auth = require('../../utils/auth');

// 手机号 / 验证码校验
function isValidPhone(value) {
  return /^1[3-9]\d{9}$/.test(String(value || ''));
}

function isValidCode(value) {
  return /^\d{6}$/.test(String(value || ''));
}

Page(
  withShare({
    data: {
      reason: '',
      // 入口引导态（启动页 mode=entry）：文案更轻，且授权后 reLaunch 到目标页
      entryMode: false,
      entryTip: '',
      fallbackVisible: false,
      phone: '',
      code: '',
      counting: false,
      countdownText: 60,
      canSubmit: false
    },
    onLoad(options) {
      const opts = options || {};
      this.reason = opts.reason ? decodeURIComponent(opts.reason) : '';
      // 入口引导态：来自启动页，引导用户绑手机号（可跳过）
      this.entryMode = (opts.mode || '') === 'entry';
      // 授权 / 跳过后的落点；来源不合法时回落首页，避免被构造参数跳转
      this.entryTarget = this.entryMode && opts.target ? decodeURIComponent(opts.target) : '/pages/home/home';
      // 标记本次授权是否已完成，供 onUnload 判断是否清理待执行动作
      this.completed = false;
      this.setData({
        reason: this.reason,
        entryMode: this.entryMode,
        entryTarget: this.entryTarget,
        entryTip: this.entryMode ? '登录后可下单、领券并同步会员权益' : ''
      });
    },
    onUnload() {
      // 用户中途返回且未完成授权时，清掉待执行动作，避免脏动作残留
      if (!this.completed) guard.clearPendingAction();
      this.stopCountdown();
    },
    toggleFallback() {
      this.setData({ fallbackVisible: !this.data.fallbackVisible });
    },

    // ---------- 微信一键获取手机号 ----------
    handleGetPhoneNumber(e) {
      const detail = e.detail || {};
      if (!detail.encryptedData || !detail.iv) {
        guard.toast('已取消授权');
        return;
      }
      api
        .bindPhone(detail.encryptedData, detail.iv)
        .then(() => this.afterBound())
        .catch(() => {
          // 失败时自动展开降级方案，避免用户困在原地
          this.setData({ fallbackVisible: true });
          guard.toast('获取失败，请使用其他登录方式');
        });
    },

    // ---------- 手机号 + 验证码 ----------
    handlePhoneInput(e) {
      const phone = e.detail.value || '';
      this.setData({ phone, canSubmit: isValidPhone(phone) && isValidCode(this.data.code) });
    },
    handleCodeInput(e) {
      const code = e.detail.value || '';
      this.setData({ code, canSubmit: isValidPhone(this.data.phone) && isValidCode(code) });
    },
    handleSendCode() {
      if (this.data.counting) return;
      const phone = this.data.phone;
      if (!isValidPhone(phone)) {
        guard.toast('请输入正确的手机号');
        return;
      }
      api
        .sendSmsCode(phone)
        .then(() => {
          guard.toast('验证码已发送');
          this.startCountdown();
        })
        .catch(error => guard.toast((error && error.message) || '发送失败'));
    },
    startCountdown() {
      this.setData({ counting: true, countdownText: 60 });
      this.timer = setInterval(() => {
        const next = this.data.countdownText - 1;
        if (next <= 0) {
          this.stopCountdown();
          this.setData({ counting: false, countdownText: 60 });
          return;
        }
        this.setData({ countdownText: next });
      }, 1000);
    },
    stopCountdown() {
      if (this.timer) {
        clearInterval(this.timer);
        this.timer = null;
      }
    },
    handleSubmitCode() {
      if (!this.data.canSubmit) {
        guard.toast('请填写完整的手机号和验证码');
        return;
      }
      api
        .bindPhoneBySms(this.data.phone, this.data.code)
        .then(() => this.afterBound())
        .catch(error => guard.toast((error && error.message) || '验证失败'));
    },

    // ---------- 授权完成：续跑原操作并返回 ----------
    afterBound() {
      this.completed = true;
      this.stopCountdown();
      guard.toast('登录成功');
      // 入口引导态：启动页已不在栈上，必须 reLaunch 到目标页，避免页面栈异常
      if (this.data.entryMode) {
        wx.reLaunch({
          url: this.data.entryTarget || '/pages/home/home',
          complete() {
            guard.flushPendingAction();
          }
        });
        return;
      }
      // 必须等返回来源页再续跑原操作：
      // navigateBack 是异步的，若立即 flush，原操作可能在授权页上下文中执行。
      wx.navigateBack({
        delta: 1,
        complete() {
          // complete 覆盖成功与失败（栈底无法返回时也要保证续跑不丢）
          guard.flushPendingAction();
        }
      });
    },

    // ---------- 跳过 ----------
    handleSkip() {
      this.completed = true;
      guard.clearPendingAction();
      // 入口引导态：跳过也必须落到目标页，不能停在授权页
      if (this.data.entryMode) {
        wx.reLaunch({ url: this.data.entryTarget || '/pages/home/home' });
        return;
      }
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: '/pages/home/home' });
    },

    // 协议改为独立页面，按类型跳转
    openAgreement(e) {
      const type = (e.currentTarget.dataset && e.currentTarget.dataset.type) || 'agreement';
      wx.navigateTo({ url: `/pages/legal/legal?type=${type}` });
    }
  })
);

