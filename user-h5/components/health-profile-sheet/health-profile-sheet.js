const tongueProfile = require('../../utils/tongue-profile');

/**
 * 舌诊个人信息弹窗（图 10）。
 *
 * 弹窗内部维护草稿（draft），只有点「保存」才把性别与出生年抛给页面；
 * 数据来源与保存都由页面处理；组件自身不读写任何缓存。
 */
Component({
  properties: {
    visible: { type: Boolean, value: false },
    gender: { type: String, value: '' },
    birthYear: { type: String, value: '' }
  },
  data: {
    draftGender: '',
    draftBirthYear: '',
    birthYearOptions: [],
    birthYearIndex: 0
  },
  lifetimes: {
    attached() {
      this.setData({ birthYearOptions: tongueProfile.birthYearOptions(new Date()) });
    }
  },
  observers: {
    visible(value) {
      if (value) this.syncDraft();
    }
  },
  methods: {
    syncDraft() {
      const birthYearOptions = this.data.birthYearOptions.length
        ? this.data.birthYearOptions
        : tongueProfile.birthYearOptions(new Date());
      const draftBirthYear =
        tongueProfile.normalizeBirthYear(this.data.birthYear) || tongueProfile.defaultTongueProfile().birthYear;
      this.setData({
        draftGender: this.data.gender || '',
        draftBirthYear,
        birthYearOptions,
        birthYearIndex: tongueProfile.birthYearIndex(draftBirthYear, birthYearOptions)
      });
    },
    handleClose() {
      this.triggerEvent('close');
    },
    selectGender(event) {
      this.setData({ draftGender: (event.currentTarget.dataset || {}).gender || '' });
    },
    handleYearChange(event) {
      const index = Number((event.detail && event.detail.value) || 0);
      if (!Number.isInteger(index) || index < 0 || index >= this.data.birthYearOptions.length) return;
      this.setData({
        draftBirthYear: this.data.birthYearOptions[index],
        birthYearIndex: index
      });
    },
    handleSave() {
      this.triggerEvent('save', {
        gender: this.data.draftGender,
        birthYear: this.data.draftBirthYear
      });
    }
  }
});
