import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const pageRoot = path.join(root, 'pages/profile-data/profile-data')
const storage = {}
const calls = []

globalThis.wx = {
  showShareMenu() {},
  getStorageSync(key) { return storage[key] || '' },
  setStorageSync(key, value) { storage[key] = value; calls.push({ type: 'setStorage', key, value }) },
  removeStorageSync(key) { delete storage[key] },
  showToast(options) { calls.push({ type: 'toast', ...options }) },
  navigateBack(options = {}) { calls.push({ type: 'navigateBack', ...options }) }
}

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${pageRoot}.${extension}`), `缺少个人资料页文件: profile-data.${extension}`)
}

const {
  PROFILE_STORAGE_KEY,
  getUserProfile,
  saveUserProfile,
  maskPhone,
  getDefaultBirthday,
  getDaysInMonth
} = require(path.join(root, 'utils/user-profile.js'))
const defaultProfile = getUserProfile()
assert.equal(maskPhone('13612345792'), '136****5792', '手机号必须按参考图脱敏')
assert.equal(getDaysInMonth(2008, 2), 29, '闰年二月必须返回 29 天')
assert.equal(getDefaultBirthday(new Date('2026-09-17T00:00:00')), '2008-09-17', '生日默认值必须为当前日期往前 18 年')

const savedProfile = saveUserProfile(Object.assign({}, defaultProfile, {
  nickname: '李小茶',
  gender: 'female',
  birthday: '2008-09-17',
  region: ['湖南省', '长沙市', '岳麓区']
}))
assert.equal(savedProfile.nickname, '李小茶', '保存后必须返回规范化的资料')
assert.equal(getUserProfile().region.join('/'), '湖南省/长沙市/岳麓区', '地区必须持久化完整省市县')
assert.ok(PROFILE_STORAGE_KEY, '必须声明资料存储键')

const { userProfile: mockUserProfile } = require(path.join(root, 'data/mock.js'))
assert.notEqual(mockUserProfile.nickname, '李小茶', '资料保存不得直接修改 Mock 原始对象')

const regions = require(path.join(root, 'data/regions.js'))
assert.ok(regions.length >= 34, '地区数据必须覆盖省级行政区')
const hunan = regions.find(region => region.name === '湖南省')
const changsha = hunan && hunan.children.find(city => city.name === '长沙市')
assert.ok(changsha && changsha.children.some(district => district.name === '岳麓区'), '地区数据必须包含湖南省长沙市岳麓区')

let definition
globalThis.Page = page => { definition = page }
require(`${pageRoot}.js`)
function createPageInstance() {
  const instance = Object.assign({}, definition)
  instance.data = Object.assign({}, definition.data)
  instance.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates)
  }
  return instance
}
const page = createPageInstance()
definition.onLoad.call(page)
assert.equal(page.data.nickname, '李小茶', '资料页必须读取本地保存的姓名')
assert.equal(page.data.gender, 'female', '资料页必须读取本地保存的性别')
assert.equal(page.data.phoneMasked, '136****5792', '资料页必须显示脱敏手机号')
assert.equal(page.data.birthdayLocked, true, '已填写生日后必须锁定')

definition.handleNameInput.call(page, { detail: { value: '王小茶' } })
definition.selectGender.call(page, { currentTarget: { dataset: { gender: 'male' } } })
definition.openRegionPicker.call(page)
assert.ok(page.data.regionVisible && page.data.regionOptions.length >= 34, '地区选择器必须从省级列表开始')
definition.selectRegionOption.call(page, { currentTarget: { dataset: { code: hunan.code } } })
assert.equal(page.data.regionStep, 1, '选择省后必须进入市级列表')
definition.selectRegionOption.call(page, { currentTarget: { dataset: { code: changsha.code } } })
assert.equal(page.data.regionStep, 2, '选择市后必须进入区县列表')
definition.selectRegionOption.call(page, { currentTarget: { dataset: { code: '430104' } } })
assert.equal(page.data.regionText, '湖南省 长沙市 岳麓区', '选择区县后必须回填完整地区')

const newPage = createPageInstance()
definition.onLoad.call(newPage, { reset: '1' })
newPage.data.nickname = ''
definition.handleSave.call(newPage)
assert.ok(calls.some(call => call.type === 'toast' && call.title === '请输入您的姓名'), '空姓名不得保存')

newPage.data.nickname = '新用户'
definition.handleSave.call(newPage)
assert.equal(getUserProfile().nickname, '新用户', '保存必须写入本地资料')
assert.ok(calls.some(call => call.type === 'navigateBack'), '保存成功后必须返回我的页')

const profileWxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8')
const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8')
assert.ok(profileWxml.includes('bindtap="openProfileData"'), '我的页头像和姓名必须接通个人资料页')
assert.ok(profileJs.includes('openProfileData') && profileJs.includes('/pages/profile-data/profile-data'), '我的页必须跳转个人资料页')
assert.ok(profileJs.includes('getUserProfile') && profileJs.includes('onShow'), '我的页返回时必须同步本地资料')

let profileDefinition
globalThis.Page = page => { profileDefinition = page }
delete require.cache[require.resolve(path.join(root, 'pages/profile/profile.js'))]
assert.doesNotThrow(() => require(path.join(root, 'pages/profile/profile.js')), '我的页脚本必须能正常加载')
assert.ok(profileDefinition && profileDefinition.data.userProfile, '我的页初始化必须包含用户资料')

const wxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8')
const wxss = fs.readFileSync(`${pageRoot}.wxss`, 'utf8')
assert.ok(wxml.includes('title="个人资料"') && wxml.includes('请输入您的姓名') && wxml.includes('完善生日，不错过惊喜'), '个人资料页必须保留参考图字段层级')
assert.equal((wxml.match(/<picker-view-column/g) || []).length, 3, '生日选择器必须使用年/月/日三列滚轮')
assert.ok(wxml.includes('region-tabs') && wxml.includes('region-options') && wxml.includes('账号管理'), '个人资料页必须包含地区步骤选择与账号管理')
assert.ok(wxml.includes('chevron-right-brand.svg') && wxml.includes('rotate-ccw-white.svg'), '个人资料页图标必须使用 Lucide 矢量图')
assert.ok(!wxml.includes('<button'), '个人资料页不得使用原生 button')
assert.ok(wxss.includes('var(--brand-green)') && wxss.includes('var(--line-color)') && wxss.includes('var(--radius-lg)') && wxss.includes('var(--radius-pill)'), '个人资料页必须遵守设计 token')
const profileColorLiterals = [...new Set([...wxss.matchAll(/#[0-9A-Fa-f]{3,8}/g)].map(match => match[0]).filter(color => color.toUpperCase() !== '#FFFFFF'))]
assert.deepEqual(profileColorLiterals, [], '个人资料页 WXSS 不得写死设计系统外的颜色')
assert.ok(!/\b\d+px\b/.test(wxss), '个人资料页 WXSS 不得使用 px')

const iconScript = fs.readFileSync(path.join(root, 'scripts/sync-lucide-icons.mjs'), 'utf8')
assert.ok(iconScript.includes("output: 'rotate-ccw-white'"), '必须生成白色 Lucide 头像编辑图标')
assert.ok(fs.existsSync(path.join(root, 'assets/icons/lucide/rotate-ccw-white.svg')), '缺少 rotate-ccw-white.svg')

console.log('个人资料录入、生日与三级地区选择测试通过')