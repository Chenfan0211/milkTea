import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const productPageRoot = path.join(root, 'pages/coupon-products/coupon-products')
const wxCalls = []
const appState = { globalData: { selectedStoreId: 'store-002' } }

globalThis.getApp = () => appState
globalThis.wx = {
  showShareMenu() {},
  navigateTo(options) { wxCalls.push({ type: 'navigateTo', ...options }) },
  navigateBack(options = {}) { wxCalls.push({ type: 'navigateBack', ...options }) },
  setStorageSync(key, value) { wxCalls.push({ type: 'setStorage', key, value }) },
  getStorageSync() { return '' },
  removeStorageSync() {},
  showToast(options) { wxCalls.push({ type: 'toast', ...options }) }
}

function loadPage(pageRoot) {
  let definition
  globalThis.Page = page => { definition = page }
  delete require.cache[require.resolve(`${pageRoot}.js`)]
  require(`${pageRoot}.js`)
  return definition
}

function createPageInstance(definition) {
  return {
    data: Object.assign({}, definition.data),
    setData(updates) { this.data = Object.assign({}, this.data, updates) }
  }
}

for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${productPageRoot}.${extension}`), `缺少适用商品页文件: coupon-products.${extension}`)
}

const { coupons, menuTabs } = require(path.join(root, 'data/mock.js'))
const coupon = coupons.find(item => item.id === 'coupon-001')
assert.ok(coupon && Array.isArray(coupon.applicableProductIds), '优惠券必须声明适用商品 ID')
assert.equal(coupon.applicableProductIds.length, 17, '适用商品必须覆盖现有十七款去重饮品')

const allMenuProducts = []
for (const menu of menuTabs) {
  for (const group of menu.groups) {
    for (const category of group.categories) {
      allMenuProducts.push(...category.products)
    }
  }
}
for (const productId of coupon.applicableProductIds) {
  assert.ok(allMenuProducts.some(product => product.id === productId), `适用商品 ID 不存在: ${productId}`)
}

const productDefinition = loadPage(productPageRoot)
const productPage = createPageInstance(productDefinition)
productDefinition.onLoad.call(productPage, { couponId: 'coupon-001', storeId: 'store-001' })
assert.equal(productPage.data.storeName, '星沙乐运魔方店', '适用商品页必须使用所选门店名')
assert.equal(productPage.data.products.length, 17, '适用商品页必须展示十七款商品')
assert.equal(new Set(productPage.data.products.map(product => product.name)).size, 17, '适用商品必须按名称去重')
assert.ok(productPage.data.products.every(product => product.sizeText === '中杯'), '杯型必须从商品规格数据生成')

const emptyProductPage = createPageInstance(productDefinition)
productDefinition.onLoad.call(emptyProductPage, { couponId: 'missing-coupon', storeId: 'store-001' })
assert.equal(emptyProductPage.data.products.length, 0, '无效优惠券不得回退为全部商品')

const storesPageRoot = path.join(root, 'pages/coupon-stores/coupon-stores')
const storesDefinition = loadPage(storesPageRoot)
const productModePage = createPageInstance(storesDefinition)
storesDefinition.onLoad.call(productModePage, { couponId: 'coupon-001', next: 'products' })
wxCalls.length = 0
storesDefinition.handleSelectStore.call(productModePage, { currentTarget: { dataset: { id: 'store-001' } } })
assert.equal(wxCalls.filter(call => call.type === 'navigateTo').length, 1, '商品模式必须跳转适用商品页')
assert.ok(wxCalls.some(call => call.url === '/pages/coupon-products/coupon-products?couponId=coupon-001&storeId=store-001'), '商品模式必须携带券号和门店号')
assert.equal(wxCalls.filter(call => call.type === 'setStorage').length, 0, '商品模式不得写入门店偏好')
assert.equal(appState.globalData.selectedStoreId, 'store-002', '商品模式不得修改全局当前门店')

const browseModePage = createPageInstance(storesDefinition)
storesDefinition.onLoad.call(browseModePage, { couponId: 'coupon-001' })
wxCalls.length = 0
storesDefinition.handleSelectStore.call(browseModePage, { currentTarget: { dataset: { id: 'store-001' } } })
assert.ok(wxCalls.some(call => call.type === 'setStorage'), '查看门店模式必须继续持久化选择')
assert.ok(wxCalls.some(call => call.type === 'navigateBack'), '查看门店模式必须继续返回上一页')

const couponListWxml = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.wxml'), 'utf8')
const couponListJs = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.js'), 'utf8')
assert.ok(couponListWxml.includes('bindtap="handleViewProducts"'), '优惠券列表必须接通查看适用商品')
assert.ok(couponListJs.includes('handleViewProducts') && couponListJs.includes('&next=products'), '查看适用商品入口必须跳转商品门店流程')

const storesWxml = fs.readFileSync(`${storesPageRoot}.wxml`, 'utf8')
assert.ok(storesWxml.includes('bindtap="handleSelectStore"'), '门店卡必须支持选择门店')
assert.ok(storesWxml.includes('catchtap="handleNavigate"'), '门店导航按钮必须阻止选择事件冒泡')

const productWxml = fs.readFileSync(`${productPageRoot}.wxml`, 'utf8')
const productWxss = fs.readFileSync(`${productPageRoot}.wxss`, 'utf8')
assert.ok(productWxml.includes('title="适用商品"') && productWxml.includes('coupon-product-grid') && productWxml.includes('item.sizeText'), '适用商品页必须保留标题、网格和杯型信息')
assert.ok(productWxml.includes('mode="aspectFit"') && productWxml.includes('ellipsis'), '商品图必须完整展示且商品名单行省略')
assert.ok(/\.coupon-product-grid\s*\{[\s\S]*?flex-wrap:\s*wrap/.test(productWxss), '适用商品必须使用三列换行网格')
assert.ok(/\.coupon-product\s*\{[\s\S]*?width:\s*calc\(\(100% - 24rpx\) \/ 3\)/.test(productWxss), '适用商品卡必须使用三列宽度与 12rpx 列间距')
assert.ok(productWxss.includes('var(--page-gutter)') && productWxss.includes('var(--line-color)') && productWxss.includes('var(--radius-sm)'), '适用商品页必须遵守设计系统 token')
assert.ok(!/font-size:\s*\d/.test(productWxss), '适用商品页字号必须使用设计 token')

console.log('优惠券适用商品展示流程测试通过')