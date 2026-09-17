const { withShare } = require('../../utils/share')
const regions = require('../../data/regions')
const {
  formatBirthday,
  getDaysInMonth,
  getDefaultBirthday,
  getUserProfile,
  maskPhone,
  saveUserProfile
} = require('../../utils/user-profile')

function pad(value) {
  return String(value).padStart(2, '0')
}

function buildYearOptions() {
  const currentYear = new Date().getFullYear()
  const years = []
  for (let year = 1900; year <= currentYear; year += 1) years.push(`${year}年`)
  return years
}

function buildMonthOptions() {
  const months = []
  for (let month = 1; month <= 12; month += 1) months.push(`${month}月`)
  return months
}

function buildDayOptions(year, month) {
  const days = []
  const count = getDaysInMonth(year, month)
  for (let day = 1; day <= count; day += 1) days.push(`${day}日`)
  return days
}

function parseBirthday(value) {
  const parts = String(value || '').split('-').map(Number)
  if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) return null
  return { year: parts[0], month: parts[1], day: parts[2] }
}

function getRegionText(region) {
  return Array.isArray(region) ? region.filter(Boolean).join(' ') : ''
}

Page(withShare({
  data: {
    avatar: '',
    nickname: '',
    gender: '',
    phoneMasked: '',
    phone: '',
    birthday: '',
    birthdayLocked: false,
    region: [],
    regionText: '',
    birthdayVisible: false,
    birthdayYears: buildYearOptions(),
    birthdayMonths: buildMonthOptions(),
    birthdayDays: [],
    birthdayValue: [0, 0, 0],
    birthdayDraft: null,
    regionVisible: false,
    regionTabs: [{ step: 0, label: '请选择', active: true }],
    regionOptions: regions,
    regionStep: 0,
    regionPath: []
  },
  onLoad() {
    this.syncProfile()
  },
  onShow() {
    this.syncProfile()
  },
  syncProfile() {
    const profile = getUserProfile()
    this.setData({
      avatar: profile.avatar,
      nickname: profile.nickname,
      gender: profile.gender,
      phone: profile.phone,
      phoneMasked: maskPhone(profile.phone),
      birthday: profile.birthday,
      birthdayLocked: Boolean(profile.birthday),
      region: profile.region,
      regionText: getRegionText(profile.region)
    })
  },
  handleNameInput(event) {
    this.setData({ nickname: event.detail.value })
  },
  selectGender(event) {
    this.setData({ gender: event.currentTarget.dataset.gender || '' })
  },
  handleAvatar() {
    wx.showToast({ title: '头像更换暂未接入', icon: 'none' })
  },
  handlePhoneChange() {
    wx.showToast({ title: '手机号更换暂未接入', icon: 'none' })
  },
  openBirthdayPicker() {
    if (this.data.birthdayLocked) {
      wx.showToast({ title: '生日填写后不可修改', icon: 'none' })
      return
    }
    const birthday = parseBirthday(this.data.birthday) || parseBirthday(getDefaultBirthday())
    const years = buildYearOptions()
    const months = buildMonthOptions()
    const days = buildDayOptions(birthday.year, birthday.month)
    const yearIndex = birthday.year - 1900
    const monthIndex = birthday.month - 1
    const dayIndex = Math.min(birthday.day - 1, days.length - 1)
    this.setData({
      birthdayVisible: true,
      birthdayYears: years,
      birthdayMonths: months,
      birthdayDays: days,
      birthdayValue: [yearIndex, monthIndex, dayIndex],
      birthdayDraft: { year: birthday.year, month: birthday.month, day: birthday.day }
    })
  },
  closeBirthdayPicker() {
    this.setData({ birthdayVisible: false })
  },
  handleBirthdayChange(event) {
    const value = event.detail.value || []
    const year = 1900 + Number(value[0] || 0)
    const month = Number(value[1] || 0) + 1
    const days = buildDayOptions(year, month)
    const dayIndex = Math.min(Number(value[2] || 0), days.length - 1)
    this.setData({
      birthdayYears: buildYearOptions(),
      birthdayMonths: buildMonthOptions(),
      birthdayDays: days,
      birthdayValue: [Number(value[0] || 0), Number(value[1] || 0), dayIndex],
      birthdayDraft: { year, month, day: dayIndex + 1 }
    })
  },
  confirmBirthday() {
    const draft = this.data.birthdayDraft
    if (!draft) return
    this.setData({
      birthday: formatBirthday(draft.year, draft.month, draft.day),
      birthdayVisible: false
    })
  },
  openRegionPicker() {
    this.setData({
      regionVisible: true,
      regionStep: 0,
      regionPath: [],
      regionOptions: regions,
      regionTabs: [{ step: 0, label: '请选择', active: true }]
    })
  },
  closeRegionPicker() {
    this.setData({ regionVisible: false })
  },
  selectRegionOption(event) {
    const { code } = event.currentTarget.dataset
    const node = this.data.regionOptions.find(item => item.code === code)
    if (!node) return
    const regionPath = this.data.regionPath.concat([node.name])
    if (Array.isArray(node.children) && node.children.length) {
      const nextStep = this.data.regionStep + 1
      const tabs = regionPath.map((label, index) => ({
        step: index,
        label,
        active: index === nextStep - 1 || index === nextStep
      }))
      if (tabs.length) tabs[tabs.length - 1].active = true
      this.setData({
        regionStep: nextStep,
        regionPath,
        regionOptions: node.children,
        regionTabs: tabs
      })
      return
    }
    this.setData({
      region: regionPath,
      regionText: getRegionText(regionPath),
      regionVisible: false
    })
  },
  jumpRegionStep(event) {
    const step = Number(event.currentTarget.dataset.step || 0)
    let options = regions
    for (let index = 0; index < step; index += 1) {
      const selectedName = this.data.regionPath[index]
      const selectedNode = options.find(item => item.name === selectedName)
      if (!selectedNode) return
      options = selectedNode.children || []
    }
    const tabs = this.data.regionPath.slice(0, step + 1).map((label, index) => ({
      step: index,
      label,
      active: index === step
    }))
    this.setData({ regionStep: step, regionOptions: options, regionTabs: tabs.length ? tabs : [{ step: 0, label: '请选择', active: true }] })
  },
  handleSave() {
    const nickname = String(this.data.nickname || '').trim()
    if (!nickname) {
      wx.showToast({ title: '请输入您的姓名', icon: 'none' })
      return
    }
    const profile = getUserProfile()
    saveUserProfile(Object.assign({}, profile, {
      nickname,
      gender: this.data.gender,
      birthday: this.data.birthday,
      region: this.data.region
    }))
    wx.showToast({ title: '保存成功', icon: 'success' })
    wx.navigateBack()
  },
  handleAccountManagement() {
    wx.showToast({ title: '账号管理暂未接入', icon: 'none' })
  }
}))