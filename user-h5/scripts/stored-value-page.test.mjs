import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const pageRoot = path.join(root, 'pages/stored-value/stored-value')

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${pageRoot}.${extension}`), `缺少会员储值页文件: stored-value.${extension}`)
}

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
assert.ok(appJson.pages.includes('pages/stored-value/stored-value'), 'app.json 必须注册会员储值页')
assert.ok(!appJson.tabBar.list.some(item => item.pagePath === 'pages/stored-value/stored-value'), '会员储值页不得加入 TabBar')

const homeJs = fs.readFileSync(path.join(root, 'pages/home/home.js'), 'utf8')
assert.ok(homeJs.includes("id === 'stored-value'") && homeJs.includes('/pages/stored-value/stored-value'), '首页储值有礼入口必须跳转会员储值页')

const profileJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8')
const profileWxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8')
assert.ok(profileJs.includes("id === 'balance'") && profileJs.includes('/pages/stored-value/stored-value'), '我的页余额入口必须跳转会员储值页')
assert.ok(profileWxml.includes('aria-role="button"') && profileWxml.includes('aria-label="{{item.label}}"'), '我的页统计入口必须补充无障碍语义')

const storedValueMock = require(path.join(root, 'data/mock.js'))
const { storedValuePackages } = storedValueMock
assert.equal(Object.prototype.hasOwnProperty.call(storedValueMock, 'storedValueRules'), false, '不得保留未使用的 storedValueRules 导出')
assert.deepEqual(
  storedValuePackages.map(item => ({ id: item.id, amount: item.amount, couponAmounts: item.coupons.map(coupon => coupon.amount) })),
  [{ id: 'stored-value-100', amount: 100, couponAmounts: [2, 3, 5] }],
  '当前只能提供一个 100 元储值套餐，并包含 2/3/5 元代金券'
)

const { buildStoredValueSummary, changeStoredValueQuantity, MAX_STORED_VALUE_QUANTITY } = require(path.join(root, 'utils/stored-value.js'))
assert.equal(MAX_STORED_VALUE_QUANTITY, 10, '储值份数上限必须为 10')
assert.equal(changeStoredValueQuantity(1, -1), 1, '数量不得低于 1')
assert.equal(changeStoredValueQuantity(10, 1), 10, '数量不得超过 10')
const summary = buildStoredValueSummary(storedValuePackages[0], 2)
assert.equal(summary.totalAmount, 200, '两份储值套餐总价必须为 200 元')
assert.deepEqual(summary.giftItems.map(item => item.quantity), [4, 4, 4], '赠送券数量必须按套餐份数倍增')
assert.ok(summary.usageParagraphs[0].includes('储值金额200元'), '使用说明首个段落必须使用当前总金额')
assert.ok(summary.usageParagraphs[0].includes('4张'), '使用说明必须使用当前赠送券数量')

let pageDefinition
globalThis.Page = definition => { pageDefinition = definition }
require(`${pageRoot}.js`)
function createBoundaryContext(quantity) {
  return {
    data: { quantity, storedValuePackage: storedValuePackages[0] },
    updateCount: 0,
    updateSummary() { this.updateCount += 1 }
  }
}
const minusBoundary = createBoundaryContext(1)
pageDefinition.changeQuantity.call(minusBoundary, { currentTarget: { dataset: { delta: -1 } } })
assert.equal(minusBoundary.updateCount, 0, '数量为 1 时不得重复更新摘要')
const plusBoundary = createBoundaryContext(MAX_STORED_VALUE_QUANTITY)
pageDefinition.changeQuantity.call(plusBoundary, { currentTarget: { dataset: { delta: 1 } } })
assert.equal(plusBoundary.updateCount, 0, '数量达到上限时不得重复更新摘要')

const pageWxml = fs.readFileSync(`${pageRoot}.wxml`, 'utf8')
const pageWxss = fs.readFileSync(`${pageRoot}.wxss`, 'utf8')
const pageJs = fs.readFileSync(`${pageRoot}.js`, 'utf8')
assert.ok(pageWxml.includes('<navigation-bar') && pageWxml.includes('title="会员储值"') && pageWxml.includes('back="{{true}}"'), '储值页必须使用带返回按钮的自定义导航栏')
assert.ok(!pageWxml.includes('search="{{true}}"')&&!pageWxml.includes('bind:search='), '储值页导航栏不得保留无功能搜索入口')
assert.ok(pageWxml.includes('stored-value-scroll') && pageWxml.includes('stored-value-bar'), '储值页必须包含滚动区和固定底部操作栏')
assert.ok(pageWxml.includes('stored-value-banner.jpg') && pageWxml.includes('gift-brand.svg') && pageWxml.includes('file-search-brand.svg'), '储值页必须使用约定的运行图和 Lucide 图标')
assert.ok(pageWxml.includes('/assets/icons/lucide/refresh-cw.svg') && !pageWxml.includes('locate-fixed.svg'), '门店切换必须使用循环箭头图标')
assert.ok(pageWxml.includes("quantity === maxQuantity ? 'is-disabled'") && pageWxml.includes('aria-disabled="{{quantity === maxQuantity}}"'), '步进器达到上限时必须展示禁用态')
assert.ok(pageWxml.includes('content-section content-section--gift'), '赠送优惠区块必须使用独立间距类')
assert.ok(!pageWxml.includes('content-section--usage'), '使用说明不得保留冗余的内容区块修饰类')
assert.ok(pageWxml.includes('hover-class="stored-value-bar__button--pressed"'), '主按钮必须提供按压反馈')
assert.ok(pageWxml.includes('balance-card__value ellipsis'), '长余额必须复用全局省略规则')
assert.ok(/\.content-section--gift\s*\{[\s\S]*?padding-top:\s*20rpx/.test(pageWxss), '充值卡到赠送优惠标题必须增加 20rpx 间距')
assert.ok(/\.gift-list\s*\{[^}]*margin-top:\s*24rpx/.test(pageWxss) && !/\.gift-list\s*\{[^}]*padding-bottom:/.test(pageWxss), '赠券列表首项间距必须规范且不得保留无效底部间距')
assert.ok(/\.gift-item\s*\{[^}]*margin-top:\s*20rpx[^}]*flex:\s*1[^}]*min-width:\s*0/.test(pageWxss), '赠券列表必须使用规范行距并支持长文案收缩')
assert.ok(/\.store-row__name\s*\{[^}]*flex:\s*1[^}]*min-width:\s*0/.test(pageWxss) && !/\.store-row__name\s*\{[^}]*max-width:/.test(pageWxss), '门店名必须可收缩且不得固定最大宽度')
assert.ok(/\.balance-card__value\s*\{[^}]*display:\s*block[^}]*max-width:\s*100%/.test(pageWxss), '余额内容必须限制在余额卡文字区内')
assert.ok(/\.quantity-stepper__button\.is-disabled\s*\{[^}]*pointer-events:\s*none/.test(pageWxss), '禁用步进器必须阻止连续点击')
assert.ok(/\.stored-value-bar__button--pressed\s*\{[^}]*background:\s*var\(--brand-dark\)/.test(pageWxss), '主按钮按压态必须使用品牌深色 token')
assert.ok(/\.usage-list__paragraph \+ \.usage-list__paragraph\s*\{[^}]*margin-top:\s*8rpx/.test(pageWxss), '使用说明段落之间必须保留 8rpx 间距')
assert.ok(/\.usage-list__paragraph\s*\{[^}]*word-break:\s*break-word/.test(pageWxss), '使用说明必须支持长文本换行')
assert.ok(!pageJs.includes('handleSearch'),'储值页不得保留无功能搜索处理函数');assert.ok(pageJs.includes('changeQuantity') && pageJs.includes('handleRecharge'), '储值页必须实现数量切换与储值交互')
assert.ok(pageWxss.includes('var(--page-gutter)') && pageWxss.includes('var(--brand-green)') && pageWxss.includes('var(--radius-lg)'), '储值页样式必须引用设计 token')
const storedValueColorLiterals = [...new Set([...pageWxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)].map(match => match[0]).filter(color => color.toUpperCase() !== '#FFFFFF'))]
assert.deepEqual(storedValueColorLiterals, [], '储值页 WXSS 不得写死设计系统外的颜色')
assert.ok(!/rgba?\(/.test(pageWxss), '储值页 WXSS 不得写死 rgba 颜色')
assert.ok(!/\b\d+px\b/.test(pageWxss), '储值页 WXSS 不得使用 px')
const spacingDeclarations = [...pageWxss.matchAll(/(?:margin|padding)(?:-[a-z]+)?\s*:\s*([^;]+)/g)]
const spacingValues = spacingDeclarations.flatMap(match => [...match[1].matchAll(/(-?\d+)rpx/g)].map(value => Number(value[1])))
const invalidSpacingValues = [...new Set(spacingValues.filter(value => ![4, 8, 12, 16, 20, 24, 32, 40].includes(value)))]
assert.deepEqual(invalidSpacingValues, [], '储值页 margin/padding 只能使用设计系统八档间距')
const fontSizeLiterals = [...pageWxss.matchAll(/font-size:\s*(\d+rpx)/g)].map(match => match[1])
assert.deepEqual(fontSizeLiterals, [], '储值页字号必须引用设计 token')

const iconScript = fs.readFileSync(path.join(root, 'scripts/sync-lucide-icons.mjs'), 'utf8')
for (const icon of ['gift-brand', 'receipt-brand', 'settings-brand', 'file-search-brand', 'plus-brand', 'refresh-cw']) {
  assert.ok(iconScript.includes(`output: '${icon}'`), `Lucide 映射必须包含 ${icon}`)
  assert.ok(fs.existsSync(path.join(root, `assets/icons/lucide/${icon}.svg`)), `缺少 Lucide 运行图标: ${icon}.svg`)
}

const checkProjectSource = fs.readFileSync(path.join(root, 'scripts/check-project.mjs'), 'utf8')
assert.ok(!checkProjectSource.includes("'pages/activity-rules/activity-rules'"), '项目结构校验不得保留孤立的 activity-rules 页面')

const imagePath = path.join(root, 'assets/images/3x/stored-value-banner.jpg')
assert.ok(fs.existsSync(imagePath), '缺少 3x 储值卡横幅图')

console.log('会员储值页路由、数据和交互测试通过')
