const loginGuard = require('../../utils/login-guard');
const userProfile = require('../../utils/user-profile');
const tongueProfile = require('../../utils/tongue-profile');

const CAPTURE_PAGE = '/packageHealth/pages/capture/capture';
const HISTORY_PAGE = '/packageHealth/pages/history/history';
const DETECT_REASON = '登录后可使用舌象体质检测';
const HISTORY_REASON = '登录后可查看体质辨识记录';

Component({
  data: {
    avatar: userProfile.DEFAULT_AVATAR,
    submitting: false,
    sheetVisible: false,
    sheetGender: '',
    sheetBirthYear: ''
  },

  lifetimes: {
    attached() {
      this.syncUser();
    }
  },

  pageLifetimes: {
    show() {
      this.syncUser();
    }
  },

  methods: {
    syncUser() {
      const cached = userProfile.getUserProfile() || {};
      this.setData({ avatar: cached.avatar || userProfile.DEFAULT_AVATAR });
    },

    loadSheetProfile() {
      return tongueProfile.resolveTongueProfile().then(profile => {
        this.setData({
          sheetGender: profile.gender,
          sheetBirthYear: profile.birthYear
        });
        return profile;
      });
    },

    openProfileSheet() {
      return loginGuard.requireLogin(() => this.loadSheetProfile().then(() => this.setData({ sheetVisible: true })), {
        reason: '登录后可完善个人信息'
      });
    },

    closeProfileSheet() {
      this.setData({ sheetVisible: false });
    },

    handleSheetSave(event) {
      const detail = (event && event.detail) || {};
      const gender = tongueProfile.normalizeGender(detail.gender);
      if (!gender) {
        wx.showToast({ title: '请选择性别', icon: 'none' });
        return;
      }
      const birthYear = tongueProfile.normalizeBirthYear(detail.birthYear);
      if (!birthYear) {
        wx.showToast({ title: '请选择出生年', icon: 'none' });
        return;
      }
      const saved = tongueProfile.saveTongueProfile({ gender, birthYear });
      if (!saved) {
        wx.showToast({ title: '个人信息保存失败，请重试', icon: 'none' });
        return;
      }
      this.setData({
        sheetVisible: false,
        sheetGender: saved.gender,
        sheetBirthYear: saved.birthYear
      });
      this.openCapture();
    },

    openCapture() {
      wx.navigateTo({ url: CAPTURE_PAGE });
    },

    startDetect() {
      if (this.data.submitting) return Promise.resolve(null);
      return loginGuard.requireLogin(() => this.loadSheetProfile().then(() => this.setData({ sheetVisible: true })), {
        reason: DETECT_REASON
      });
    },

    openPlan() {
      wx.showToast({ title: '调理方案暂未接入', icon: 'none' });
    },

    openHistory() {
      loginGuard.requireLogin(() => wx.navigateTo({ url: HISTORY_PAGE }), { reason: HISTORY_REASON });
    }
  }
});
