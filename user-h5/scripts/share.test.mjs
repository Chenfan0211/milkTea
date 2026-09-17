import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
let relaunchedTo = ''
globalThis.wx = {
  showShareMenu() {},
  reLaunch({ url }) {
    relaunchedTo = url
  }
}
const {
  DEFAULT_SHARE_IMAGE,
  buildShareAppMessage,
  buildShareTimeline,
  getShareTitle,
  withShare
} = require(path.join(root, 'utils/share.js'))

assert.equal(DEFAULT_SHARE_IMAGE, '/assets/images/3x/share-home.jpg', '默认分享封面路径必须正确')
assert.equal(getShareTitle('pages/home/home'), '五零时光新中式养生茶饮', '首页分享标题必须正确')
assert.equal(getShareTitle('pages/menu/menu'), '五零时光点单', '点单页分享标题必须正确')

const homeShare = buildShareAppMessage('pages/home/home', {})
assert.deepEqual(homeShare, {
  title: '五零时光新中式养生茶饮',
  path: '/pages/home/home',
  imageUrl: DEFAULT_SHARE_IMAGE
}, '公开首页好友分享必须进入首页')

const exchangeShare = buildShareAppMessage('pages/points-exchange/points-exchange', { id: 'points-pet-food', from: 'test' })
assert.equal(exchangeShare.path, '/pages/points-exchange/points-exchange?id=points-pet-food', '积分兑换页必须保留 id 并过滤其他参数')

const privateShare = buildShareAppMessage('pages/stored-value/stored-value', {})
assert.equal(privateShare.path, '/pages/home/home', '私密页好友分享必须进入首页')

const privateTimeline = buildShareTimeline('pages/orders/orders', { id: 'secret' })
assert.equal(privateTimeline.query, 'shareScene=timeline', '私密页朋友圈分享必须携带重定向标记')

const publicTimeline = buildShareTimeline('pages/coupon-stores/coupon-stores', { couponId: 'coupon-1', token: 'secret' })
assert.equal(publicTimeline.query, 'couponId=coupon-1', '公开页朋友圈分享必须只保留安全参数')

const productShare = buildShareAppMessage('pages/coupon-products/coupon-products', { couponId: 'coupon-001', storeId: 'store-001', token: 'secret' })
assert.equal(productShare.path, '/pages/coupon-products/coupon-products?couponId=coupon-001&storeId=store-001', '适用商品页必须保留券号和门店号分享参数')

let originalLoadCalled = false
const wrapped = withShare({
  route: 'pages/stored-value/stored-value',
  onLoad() {
    originalLoadCalled = true
  }
})
const pageInstance = Object.assign({ route: 'pages/stored-value/stored-value' }, wrapped)
pageInstance.onLoad({ shareScene: 'timeline' })
assert.equal(relaunchedTo, '/pages/home/home', '私密页朋友圈进入必须重定向首页')
assert.equal(originalLoadCalled, false, '私密页朋友圈重定向时不得执行原页面加载逻辑')

const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
for (const page of appJson.pages) {
  const source = fs.readFileSync(path.join(root, `${page}.js`), 'utf8')
  assert.ok(source.includes('withShare('), `${page} 必须使用 withShare 分享包装器`)
}

console.log('全页面分享规则与覆盖测试通过')
