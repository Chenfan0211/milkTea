const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const auth = require('../../utils/auth');
const { clearSession } = require('../../utils/auth');
const {
  DEFAULT_AVATAR,
  formatBirthday,
  getDaysInMonth,
  getDefaultBirthday,
  getUserProfile,
  maskPhone,
  refreshUserProfileFromRemote,
  saveUserProfile
} = require('../../utils/user-profile');

function pad(value) {
  return String(value).padStart(2, '0');
}

function buildYearOptions() {
  const currentYear = new Date().getFullYear();
  const years = [];
  for (let year = 1900; year <= currentYear; year += 1) years.push(`${year}年`);
  return years;
}

function buildMonthOptions() {
  const months = [];
  for (let month = 1; month <= 12; month += 1) months.push(`${month}月`);
  return months;
}

function buildDayOptions(year, month) {
  const days = [];
  const count = getDaysInMonth(year, month);
  for (let day = 1; day <= count; day += 1) days.push(`${day}日`);
  return days;
}

function parseBirthday(value) {
  const parts = String(value || '')
    .split('-')
    .map(Number);
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) return null;
  return { year: parts[0], month: parts[1], day: parts[2] };
}

Page(
  withShare({
    data: {
      avatar: '',
      defaultAvatar: DEFAULT_AVATAR,
      nickname: '',
      gender: '',
      phoneMasked: '',
      phone: '',
      birthday: '',
      birthdayLocked: false,
      address: '',
      birthdayVisible: false,
      birthdayYears: buildYearOptions(),
      birthdayMonths: buildMonthOptions(),
      birthdayDays: [],
      birthdayValue: [0, 0, 0],
      birthdayDraft: null
    },
    onLoad() {
      this.syncProfile();
    },
    onShow() {
      this.syncProfile();
    },
    syncProfile() {
      const profile = getUserProfile();
      this.setData({
        avatar: profile.avatar,
        nickname: profile.nickname,
        gender: profile.gender,
        phone: profile.phone,
        phoneMasked: maskPhone(profile.phone),
        birthday: profile.birthday,
        birthdayLocked: Boolean(profile.birthday),
        address: profile.address || ''
      });
    },
    handleNameInput(event) {
      this.setData({ nickname: event.detail.value });
    },
    selectGender(event) {
      this.setData({ gender: event.currentTarget.dataset.gender || '' });
    },
    // 头像加载失败（URL 失效 / 历史脏数据）：回落到默认头像
    handleAvatarError() {
      if (this.data.avatar === DEFAULT_AVATAR) return;
      this.setData({ avatar: DEFAULT_AVATAR });
    },
    handleAvatar() {
      // 新版头像选择：chooseAvatar 无需授权弹窗，返回临时文件路径
      if (typeof wx === 'undefined' || !wx.chooseAvatar) {
        wx.showToast({ title: '当前环境不支持选择头像', icon: 'none' });
        return;
      }
      wx.chooseAvatar({
        success: res => {
          const tempPath = res && res.avatarUrl;
          if (!tempPath) {
            wx.showToast({ title: '未选择头像', icon: 'none' });
            return;
          }
          this.uploadAvatar(tempPath);
        },
        fail: () => {
          wx.showToast({ title: '选择头像失败', icon: 'none' });
        }
      });
    },
    /** 读取临时头像文件并转 base64 后上传（方案 B：base64 直接存库） */
    uploadAvatar(tempPath) {
      const fs = typeof wx !== 'undefined' ? wx.getFileSystemManager : null;
      if (!fs) {
        wx.showToast({ title: '读取文件失败', icon: 'none' });
        return;
      }
      fs.readFile({
        filePath: tempPath,
        encoding: 'base64',
        success: res => {
          const base64 = res.data;
          if (!base64) {
            wx.showToast({ title: '读取头像失败', icon: 'none' });
            return;
          }
          // 拼 data URI（后端直接存字符串，展示时可作 <image> src）
          const dataUri = 'data:image/png;base64,' + base64;
          api
            .updateAvatar(dataUri)
            .then(() => {
              this.setData({ avatar: dataUri });
              saveUserProfile(Object.assign({}, getUserProfile(), { avatar: dataUri }));
              wx.showToast({ title: '头像已更新', icon: 'success' });
            })
            .catch(() => {
              wx.showToast({ title: '头像保存失败', icon: 'none' });
            });
        },
        fail: () => {
          wx.showToast({ title: '读取头像失败', icon: 'none' });
        }
      });
    },
    /**
     * 更换手机号：原生 getPhoneNumber 授权后提交后端。
     * 与 components/login-sheet 同一套口径：
     *   · 未注册用户走 registerByPhone（需 registerContext.registerToken）
     *   · 已登录用户走 api.bindPhone
     *   · session_key 失效时清会话并提示用户重新点击授权
     * 授权前必须先 `preparePhoneAuthorization()` 刷新会话，否则 encryptedData
     * 会绑定到过期 session_key（与 login-sheet 的 attached 预刷新同理）。
     */
    handlePhoneChange(event) {
      const detail = (event && event.detail) || {};
      if (!detail.encryptedData || !detail.iv) {
        wx.showToast({ title: '已取消授权', icon: 'none' });
        return;
      }
      wx.showLoading({ title: '更换中', mask: true });
      return auth
        .preparePhoneAuthorization()
        .then(prepared => {
          const registerContext = (prepared && prepared.registerContext) || null;
          const needRegister = Boolean(
            prepared && prepared.needRegister && registerContext && registerContext.registerToken
          );
          return needRegister
            ? auth.registerByPhone(registerContext.registerToken, detail.encryptedData, detail.iv)
            : api.bindPhone(detail.encryptedData, detail.iv);
        })
        .then(() => refreshUserProfileFromRemote())
        .then(profile => {
          const nextPhone = (profile && profile.phone) || '';
          saveUserProfile(Object.assign({}, getUserProfile(), { phone: nextPhone }));
          this.setData({ phone: nextPhone, phoneMasked: maskPhone(nextPhone) });
          wx.hideLoading();
          wx.showToast({ title: '手机号已更新', icon: 'success' });
        })
        .catch(error => {
          wx.hideLoading();
          if (auth.isSessionInvalidError(error)) {
            // encryptedData/iv 已绑定旧 session_key，必须丢弃并要求用户重新点击授权
            clearSession();
            auth.clearRegisterContext();
            wx.showToast({ title: '授权会话已刷新，请再次点击同意', icon: 'none' });
            return;
          }
          wx.showToast({ title: (error && error.message) || '手机号更新失败', icon: 'none' });
        });
    },
    openBirthdayPicker() {
      if (this.data.birthdayLocked) {
        wx.showToast({ title: '生日填写后不可修改', icon: 'none' });
        return;
      }
      const birthday = parseBirthday(this.data.birthday) || parseBirthday(getDefaultBirthday());
      const years = buildYearOptions();
      const months = buildMonthOptions();
      const days = buildDayOptions(birthday.year, birthday.month);
      const yearIndex = birthday.year - 1900;
      const monthIndex = birthday.month - 1;
      const dayIndex = Math.min(birthday.day - 1, days.length - 1);
      this.setData({
        birthdayVisible: true,
        birthdayYears: years,
        birthdayMonths: months,
        birthdayDays: days,
        birthdayValue: [yearIndex, monthIndex, dayIndex],
        birthdayDraft: { year: birthday.year, month: birthday.month, day: birthday.day }
      });
    },
    closeBirthdayPicker() {
      this.setData({ birthdayVisible: false });
    },
    handleBirthdayChange(event) {
      const value = event.detail.value || [];
      const year = 1900 + Number(value[0] || 0);
      const month = Number(value[1] || 0) + 1;
      const days = buildDayOptions(year, month);
      const dayIndex = Math.min(Number(value[2] || 0), days.length - 1);
      this.setData({
        birthdayYears: buildYearOptions(),
        birthdayMonths: buildMonthOptions(),
        birthdayDays: days,
        birthdayValue: [Number(value[0] || 0), Number(value[1] || 0), dayIndex],
        birthdayDraft: { year, month, day: dayIndex + 1 }
      });
    },
    confirmBirthday() {
      const draft = this.data.birthdayDraft;
      if (!draft) return;
      this.setData({
        birthday: formatBirthday(draft.year, draft.month, draft.day),
        birthdayVisible: false
      });
    },
    handleAddressInput(event) {
      this.setData({ address: event.detail.value });
    },
    handleSave() {
      const nickname = String(this.data.nickname || '').trim();
      if (!nickname) {
        wx.showToast({ title: '请输入您的姓名', icon: 'none' });
        return;
      }
      const birthday = String(this.data.birthday || '').trim();
      // 生日必填：资料完整度依赖生日（会员权益 / 生日礼）
      if (!birthday) {
        wx.showToast({ title: '请选择您的生日', icon: 'none' });
        return;
      }
      if (this.saving) return;
      this.saving = true;
      wx.showLoading({ title: '保存中', mask: true });
      // 必须提交后端：只写本地会在下次冷启动被 refreshUserProfileFromRemote 覆盖
      api
        .updateProfileFields({
          nickName: nickname,
          gender: this.data.gender,
          birthday,
          address: String(this.data.address || '').trim()
        })
        .then(() => refreshUserProfileFromRemote())
        .then(() => {
          this.saving = false;
          wx.hideLoading();
          wx.showToast({ title: '保存成功', icon: 'success' });
          wx.navigateBack();
        })
        .catch(error => {
          this.saving = false;
          wx.hideLoading();
          wx.showToast({ title: (error && error.message) || '保存失败，请重试', icon: 'none' });
        });
    },
    handleLogout() {
      wx.showModal({
        title: '退出登录',
        content: '确定要退出当前账号吗？',
        success: res => {
          if (!res.confirm) return;
          clearSession();
          wx.reLaunch({ url: '/pages/auth-login/auth-login' });
        }
      });
    }
  })
);
