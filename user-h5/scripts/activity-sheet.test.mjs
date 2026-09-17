import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const componentRoot = path.join(root, 'components/activity-sheet/activity-sheet')

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${componentRoot}.${extension}`), `缺少活动优惠弹层文件: activity-sheet.${extension}`)
}

const { menuActivity } = require(path.join(root, 'data/mock.js'))
assert.deepEqual(menuActivity, {
  tag: '会员优惠',
  description: '周四会员日招牌饮品85折',
  detailTitle: '周四会员日',
  rules: [
    { label: '活动时间', value: '每周四' },
    { label: '活动周期', value: '长期有效' },
    { label: '活动时段', value: '门店营业时间内' },
    { label: '总次数限制', value: '不限制' },
    { label: '每天参与次数', value: '不限制' }
  ],
  applicableProducts: '点单页标记参与活动的招牌饮品享85折优惠。',
  excludedProducts: '礼品卡、储值套餐、配送费、包装费及未标记参与活动的商品不参与本优惠。'
}, '活动规则必须保持单一数据源')

const menuJson = JSON.parse(fs.readFileSync(path.join(root, 'pages/menu/menu.json'), 'utf8'))
assert.equal(menuJson.usingComponents['activity-sheet'], '/components/activity-sheet/activity-sheet', '点单页必须注册活动优惠弹层')

const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8')
const menuJs = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8')
assert.ok(menuWxml.includes('{{menuActivity.description}}') && menuWxml.includes('bindtap="openActivitySheet"'), '红框入口必须绑定活动弹层并复用活动文案')
assert.ok(menuWxml.includes('aria-role="button"') && menuWxml.includes('aria-label="查看活动优惠"'), '活动查看入口必须提供无障碍语义')
assert.ok(menuWxml.includes('<activity-sheet') && menuWxml.includes('visible="{{activityVisible}}"') && menuWxml.includes('store="{{currentStore}}"') && menuWxml.includes('activity="{{menuActivity}}"') && menuWxml.includes('bind:view="openActivityRules"'), '点单页必须绑定弹层状态、活动和规则页跳转')
assert.ok(!menuWxml.includes('data-label="优惠详情" bindtap="showUnavailable"'), '红框入口不得继续调用暂未接入提示')
assert.ok(menuJs.includes('menuActivity') && menuJs.includes('activityVisible: false') && menuJs.includes('openActivitySheet()') && menuJs.includes('closeActivitySheet()'), '点单页必须管理活动弹层状态')
assert.ok(menuJs.includes('openActivityRules()') && menuJs.includes("'/pages/activity-rules/activity-rules'"), '查看按钮必须跳转活动规则页')
assert.ok(menuJs.includes('handleActivityPhone') && menuJs.includes('handleActivityUnavailable'), '点单页必须处理电话和未接入操作')

const componentWxml = fs.readFileSync(`${componentRoot}.wxml`, 'utf8')
const componentWxss = fs.readFileSync(`${componentRoot}.wxss`, 'utf8')
const componentJs = fs.readFileSync(`${componentRoot}.js`, 'utf8')
for (const content of ['活动优惠', '优惠活动', '门店信息', 'activity.tag', 'activity.description', 'store.address', 'store.businessHours', '查看门店服务资质', '查看食品安全档案']) {
  assert.ok(componentWxml.includes(content), `活动弹层缺少内容: ${content}`)
}
assert.ok(componentWxml.includes('activity-sheet__mask') && componentWxml.includes('bindtap="handleClose"'), '活动弹层必须支持遮罩关闭')
assert.ok(componentWxml.includes('aria-label="关闭活动优惠"') && componentWxml.includes('x.svg'), '活动弹层必须提供关闭按钮')
assert.ok(componentWxml.includes('aria-label="拨打门店电话"') && componentWxml.includes('phone.svg'), '活动弹层必须提供电话按钮')
assert.ok(componentWxml.includes('bindtap="handleView"') && componentWxml.includes('aria-label="查看优惠详情"') && componentWxml.includes('aria-label="查看门店服务资质"') && componentWxml.includes('aria-label="查看食品安全档案"'), '弹层内操作必须补充正确的跳转和无障碍属性')
assert.ok(!componentWxml.includes('data-label="优惠详情" bindtap="handleUnavailable"'), '查看按钮不得继续派发未接入事件')
assert.ok(componentJs.includes('visible: { type: Boolean') && componentJs.includes('activity: { type: Object') && componentJs.includes('store: { type: Object'), '活动弹层必须声明可见、活动和门店属性')
assert.ok(componentJs.includes("triggerEvent('close')") && componentJs.includes("triggerEvent('phone')") && componentJs.includes("triggerEvent('view')") && componentJs.includes("triggerEvent('unavailable'"), '活动弹层必须派发关闭、电话、查看和未接入事件')
assert.ok(/\.activity-sheet__panel\s*\{[^}]*bottom:\s*0[^}]*height:\s*780rpx[^}]*max-height:\s*80vh/.test(componentWxss), '活动弹层必须贴底并使用参考图高度')
assert.ok(/\.activity-sheet__link\s*\{[^}]*display:\s*flex/.test(componentWxss), '门店资质入口必须各占独立一行')
assert.ok(componentWxss.includes('border-radius: var(--radius-lg) var(--radius-lg) 0 0'), '活动弹层必须使用设计系统顶部圆角')
assert.ok(componentWxss.includes('var(--page-gutter)') && componentWxss.includes('var(--brand-green)') && componentWxss.includes('var(--card-bg)'), '活动弹层必须使用设计 token')
assert.ok(!/\b\d+px\b/.test(componentWxss), '活动弹层 WXSS 不得使用 px')
const colorLiterals = [...new Set([...componentWxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)].map(match => match[0]).filter(color => color.toUpperCase() !== '#FFFFFF'))]
assert.deepEqual(colorLiterals, [], '活动弹层 WXSS 不得写死品牌颜色')

const activityRulesRoot = path.join(root, 'pages/activity-rules/activity-rules')
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${activityRulesRoot}.${extension}`), `缺少活动规则页文件: activity-rules.${extension}`)
}
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
assert.ok(appJson.pages.includes('pages/activity-rules/activity-rules'), 'app.json 必须注册活动规则页')
const activityRulesWxml = fs.readFileSync(`${activityRulesRoot}.wxml`, 'utf8')
const activityRulesWxss = fs.readFileSync(`${activityRulesRoot}.wxss`, 'utf8')
const activityRulesJs = fs.readFileSync(`${activityRulesRoot}.js`, 'utf8')
assert.ok(activityRulesWxml.includes('<navigation-bar title="{{menuActivity.detailTitle}}"') && activityRulesWxml.includes('back="{{true}}"'), '活动规则页必须使用动态标题和返回导航')
assert.ok(activityRulesWxml.includes('活动规则') && activityRulesWxml.includes('menuActivity.rules') && activityRulesWxml.includes('活动商品') && activityRulesWxml.includes('applicableProducts') && activityRulesWxml.includes('excludedProducts'), '活动规则页必须展示完整规则和商品范围')
assert.ok(activityRulesJs.includes('withShare') && activityRulesJs.includes('menuActivity'), '活动规则页必须接入分享和活动数据源')
assert.ok(!/\b\d+px\b/.test(activityRulesWxss) && !/#[0-9A-Fa-f]{3,8}\b/.test(activityRulesWxss), '活动规则页样式必须使用设计 token')
const shareSource = fs.readFileSync(path.join(root, 'utils/share.js'), 'utf8')
assert.ok(shareSource.includes("'pages/activity-rules/activity-rules': '五零时光会员日活动规则'"), '活动规则页必须配置分享标题')
console.log('点单活动优惠弹层结构与交互测试通过')