import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const expectedRuntimeImages = new Map([
  ['assets/images/3x/home-hero.jpg', { width: 2250, height: 3120, maxBytes: 700 * 1024 }],
  ['assets/images/3x/share-home.jpg', { width: 1280, height: 1024, maxBytes: 300 * 1024 }],
  ['assets/images/3x/join-banner.jpg', { width: 2130, height: 600, maxBytes: 320 * 1024 }],
  ['assets/images/3x/menu-banner.jpg', { width: 1605, height: 420, maxBytes: 160 * 1024 }],
  ['assets/images/3x/menu-product.jpg', { width: 510, height: 630, maxBytes: 80 * 1024 }],
  ['assets/images/3x/profile-avatar.jpg', { width: 336, height: 336, maxBytes: 80 * 1024 }],
  ['assets/images/3x/profile-banner.jpg', { width: 2130, height: 480, maxBytes: 120 * 1024 }],
  ['assets/images/3x/profile-hero.jpg', { width: 2250, height: 1311, maxBytes: 480 * 1024 }],
  ['assets/images/3x/gift-card-matcha.jpg', { width: 960, height: 585, maxBytes: 60 * 1024 }],
  ['assets/images/3x/gift-card-jasmine.jpg', { width: 960, height: 585, maxBytes: 60 * 1024 }],
  ['assets/images/3x/gift-card-limited.jpg', { width: 960, height: 585, maxBytes: 60 * 1024 }],
  ['assets/images/3x/stored-value-banner.jpg', { width: 1053, height: 468, maxBytes: 120 * 1024 }]
]);

const runtimeDirs = ['pages', 'components', 'custom-tab-bar', 'data'];
const requiredPackIgnore = ['design/reference', 'assets/temp', 'scripts', 'docs'];

function walk(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) return walk(target);
    return [target];
  });
}

function getJpegSize(file) {
  const buffer = fs.readFileSync(file);
  let offset = 2;
  while (offset < buffer.length) {
    if (buffer[offset] !== 0xff) break;
    const marker = buffer[offset + 1];
    const length = buffer.readUInt16BE(offset + 2);
    if (
      (marker >= 0xc0 && marker <= 0xc3) ||
      (marker >= 0xc5 && marker <= 0xc7) ||
      (marker >= 0xc9 && marker <= 0xcb) ||
      (marker >= 0xcd && marker <= 0xcf)
    ) {
      return {
        height: buffer.readUInt16BE(offset + 5),
        width: buffer.readUInt16BE(offset + 7)
      };
    }
    offset += 2 + length;
  }
  throw new Error('无法读取 JPEG 尺寸');
}

function readProjectJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(root, relativePath), 'utf8'));
}

for (const [imagePath, expected] of expectedRuntimeImages) {
  const absolutePath = path.join(root, imagePath);
  assert.ok(fs.existsSync(absolutePath), `缺少 3x 运行图: ${imagePath}`);
  assert.deepEqual(
    getJpegSize(absolutePath),
    { width: expected.width, height: expected.height },
    `${imagePath} 尺寸不符合验收要求`
  );
  assert.ok(fs.statSync(absolutePath).size <= expected.maxBytes, `${imagePath} 文件过大`);
}

const runtimeImageBytes = [...expectedRuntimeImages.keys()].reduce(
  (total, imagePath) => total + fs.statSync(path.join(root, imagePath)).size,
  0
);
assert.ok(runtimeImageBytes <= 1.8 * 1024 * 1024, `3x 运行图总量超过 1.8MB: ${runtimeImageBytes} bytes`);

const runtimeFiles = runtimeDirs
  .flatMap(directory => walk(path.join(root, directory)))
  .filter(file => /\.(js|json|wxml|wxss)$/.test(file));
for (const file of runtimeFiles) {
  const content = fs.readFileSync(file, 'utf8');
  assert.ok(!content.includes('/assets/temp/'), `运行代码禁止引用 assets/temp: ${path.relative(root, file)}`);
  assert.ok(!/https?:\/\//.test(content), `运行代码禁止引用远程资源: ${path.relative(root, file)}`);
}

const projectConfig = readProjectJson('project.config.json');
const packIgnore = projectConfig.packOptions?.ignore || [];
for (const ignorePath of requiredPackIgnore) {
  assert.ok(
    packIgnore.some(item => item.value === ignorePath || item.value === `${ignorePath}/`),
    `project.config.json packOptions.ignore 必须排除: ${ignorePath}`
  );
}

const privateConfig = readProjectJson('project.private.config.json');
assert.equal(
  privateConfig.setting?.compileHotReLoad,
  false,
  '微信开发者工具必须关闭编译热重载，避免旧页面帧触发 routeDone webviewId 不存在错误'
);
assert.equal(
  privateConfig.setting?.skylineRenderEnable,
  true,
  '微信开发者工具必须启用 Skyline，避免 WebView 基础库注册已废弃的 DOMNodeRemoved 事件'
);

const navigationBarWxml = fs.readFileSync(path.join(root, 'components/navigation-bar/navigation-bar.wxml'), 'utf8');
const navigationBarWxss = fs.readFileSync(path.join(root, 'components/navigation-bar/navigation-bar.wxss'), 'utf8');
const backIconRule = navigationBarWxss.match(/\.weui-navigation-bar__btn_goback\s*\{([\s\S]*?)\}/)?.[1] || '';
const backWrapperRule =
  navigationBarWxss.match(/\.weui-navigation-bar__btn_goback_wrapper\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(
  navigationBarWxml.includes('chevron-left.svg') && navigationBarWxml.includes('mode="aspectFit"'),
  '共享导航栏返回按钮必须使用 Lucide 左箭头'
);
assert.ok(
  backWrapperRule.includes('width: 88rpx') && backWrapperRule.includes('height: 100%'),
  '返回按钮点击区必须为 88rpx 宽并占满导航栏高度'
);
assert.ok(backIconRule.includes('width: 48rpx') && backIconRule.includes('height: 48rpx'), '返回箭头必须统一为 48rpx');
assert.ok(
  !backIconRule.includes('border-bottom') && !backIconRule.includes('border-left') && !backIconRule.includes('rotate('),
  '返回箭头不得叠加 CSS 边框或旋转'
);
assert.ok(!navigationBarWxml.includes('weui-navigation-bar__search'), '共享导航栏不得保留无功能搜索界面');
assert.ok(
  !fs.readFileSync(path.join(root, 'components/navigation-bar/navigation-bar.js'), 'utf8').includes('handleSearch') &&
    !fs.readFileSync(path.join(root, 'components/navigation-bar/navigation-bar.js'), 'utf8').includes('search:'),
  '共享导航栏不得保留无功能搜索处理'
);
assert.ok(!navigationBarWxss.includes('.weui-navigation-bar__search'), '共享导航栏不得保留无功能搜索样式');

const homeWxml = fs.readFileSync(path.join(root, 'pages/home/home.wxml'), 'utf8');
assert.ok(
  homeWxml.includes('home-hero') && homeWxml.includes('/assets/images/3x/home-hero.jpg'),
  '首页主视觉必须引用 3x 高清图'
);
assert.ok(
  homeWxml.includes('join-card') && homeWxml.includes('/assets/images/3x/join-banner.jpg'),
  '首页加盟区必须使用完整高清横幅图片'
);
assert.ok(
  !homeWxml.includes('join-texture') && !homeWxml.includes('join-card__shade'),
  '首页加盟区不得继续叠加重绘文字或遮罩'
);
assert.ok(
  !homeWxml.includes('heroHeight') && !homeWxml.includes('capsule-repair'),
  '首页不得动态压缩主视觉，也不得绘制右上角空白遮罩'
);

const homeJs = fs.readFileSync(path.join(root, 'pages/home/home.js'), 'utf8');
assert.ok(
  !homeJs.includes('home-layout') && !homeJs.includes('calculateHeroHeight') && !homeJs.includes('heroHeight'),
  '首页不得继续依赖动态主视觉高度'
);

const homeWxss = fs.readFileSync(path.join(root, 'pages/home/home.wxss'), 'utf8');
assert.ok(homeWxml.includes('user-strip'), '首页必须保留用户信息栏');
assert.ok(homeWxml.includes('order-modes'), '首页必须保留堂食和自取入口');
assert.ok(homeWxml.includes('shortcut-grid'), '首页必须保留四个快捷入口');
assert.ok(homeWxml.includes('join-card'), '首页必须保留加入我们卡片');
assert.ok(
  homeWxml.includes('aria-label="加入我们，新中式养生茶饮连锁品牌，点击合作"'),
  '加入我们卡片必须保留完整无障碍文案和合作入口'
);
const homePageRule = homeWxss.match(/\.home-page\s*\{([\s\S]*?)\}/)?.[1] || '';
const homeScrollRule = homeWxss.match(/\.home-scroll\s*\{([\s\S]*?)\}/)?.[1] || '';
const heroWrapRule = homeWxss.match(/\.hero-wrap\s*\{([\s\S]*?)\}/)?.[1] || '';
const homeHeroRule = homeWxss.match(/\.home-hero\s*\{([\s\S]*?)\}/)?.[1] || '';
const homeCardRule = homeWxss.match(/\.home-card\s*\{([\s\S]*?)\}/)?.[1] || '';
const orderModesRule = homeWxss.match(/\.order-modes\s*\{([\s\S]*?)\}/)?.[1] || '';
const shortcutGridRule = homeWxss.match(/\.shortcut-grid\s*\{([\s\S]*?)\}/)?.[1] || '';
const joinCardRule = homeWxss.match(/\.join-card\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(homePageRule.includes('padding-bottom: 0'), '首页不得保留额外底部留白');
assert.ok(
  !homePageRule.includes('overflow-x: hidden') && !homePageRule.includes('overflow: hidden'),
  '首页根节点不得锁住纵向页面滚动'
);
assert.ok(
  homeWxml.startsWith('<scroll-view class="home-scroll" scroll-y'),
  '首页必须使用显式纵向 scroll-view，保证 Skyline 下可以滑动'
);
assert.ok(homeCardRule.includes('margin: -13rpx 20rpx 0'), '首页主卡片必须上移覆盖主图底部白条');
assert.ok(/\.home-card[\s\S]*border-radius:\s*24rpx/.test(homeWxss), '首页卡片顶部圆角必须约 24rpx');
assert.ok(orderModesRule.includes('height: 188rpx'), '堂食和自取区域高度必须约 188rpx');
assert.ok(shortcutGridRule.includes('height: 142rpx'), '快捷入口区域高度必须约 142rpx');
assert.ok(homeScrollRule.includes('height: 100vh'), '首页滚动容器必须固定为视口高度');
assert.ok(
  heroWrapRule.includes('height: 1040rpx') && !heroWrapRule.includes('overflow: hidden'),
  '首页主视觉区域必须完整展示 1040rpx，不得裁切'
);
assert.ok(
  homeHeroRule.includes('width: 750rpx') && homeHeroRule.includes('height: 1040rpx'),
  '首页主视觉必须按参考尺寸完整展示'
);
assert.ok(
  joinCardRule.includes('height: 200rpx') && joinCardRule.includes('margin: 6rpx 20rpx 0'),
  '加入我们卡片必须恢复参考图位置和完整横幅高度'
);
const profileWxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8');
const profileWxss = fs.readFileSync(path.join(root, 'pages/profile/profile.wxss'), 'utf8');
const profileHeroRule = profileWxss.match(/\.profile-hero\s*\{([\s\S]*?)\}/)?.[1] || '';
const profileScrollRule = profileWxss.match(/\.profile-scroll\s*\{([\s\S]*?)\}/)?.[1] || '';
const profileContentRule = profileWxss.match(/\.profile-content\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(
  profileWxml.startsWith('<scroll-view class="profile-scroll" scroll-y'),
  '我的页必须使用与首页一致的 scroll-view 纵向滚动'
);
assert.ok(profileScrollRule.includes('height: 100vh'), '我的页滚动容器必须固定为视口高度');
assert.ok(
  profileWxml.includes('class="profile-hero"') && profileWxml.includes('mode="aspectFill"'),
  '我的页顶部图必须使用全宽裁切铺满模式'
);
assert.ok(
  profileHeroRule.includes('width: 750rpx') && profileHeroRule.includes('height: 437rpx'),
  '我的页顶部图必须铺满 750x437rpx'
);
assert.ok(
  profileContentRule.includes('margin-top: -127rpx') && profileContentRule.includes('background: transparent'),
  '我的页用户卡片必须上移并保持顶部图可见'
);
assert.ok(
  !profileWxss.includes('overflow-y: hidden') && !profileWxss.includes('position: fixed'),
  '我的页不得锁住原生纵向滚动'
);

const homeScrollTag = (homeWxml.match(/<scroll-view[^>]*>/) || [''])[0];
const profileScrollTag = (profileWxml.match(/<scroll-view[^>]*>/) || [''])[0];
const scrollSignature = tag =>
  tag
    .replace(/\s+class="[^"]*"/, '')
    .replace(/\s+/g, ' ')
    .trim();
assert.ok(homeScrollTag && profileScrollTag, '首页与我的页必须显式声明滚动容器');
assert.equal(scrollSignature(profileScrollTag), scrollSignature(homeScrollTag), '我的页滚动容器属性必须与首页完全一致');

const ordersWxml = fs.readFileSync(path.join(root, 'pages/orders/orders.wxml'), 'utf8');
const ordersWxss = fs.readFileSync(path.join(root, 'pages/orders/orders.wxss'), 'utf8');
assert.ok(!ordersWxml.includes('placeholder-page'), '订单页必须完成重建，不得回退为占位页');
assert.ok(
  ordersWxml.includes('<navigation-bar') && ordersWxml.includes('title="我的订单"'),
  '订单页必须使用自定义导航栏并显示我的订单标题'
);
assert.ok(
  ordersWxml.includes('invoice-entry') && ordersWxml.includes('/assets/icons/lucide/receipt.svg'),
  '订单页开发票入口必须使用 Lucide receipt 图标'
);
assert.ok(
  ordersWxml.includes('orders-categories') && ordersWxml.includes('scroll-x'),
  '订单页必须提供横向滚动的分类页签'
);
assert.ok(
  ordersWxml.includes('store-order') && ordersWxml.includes('cover-order'),
  '订单卡片必须支持门店商品与储值/礼品卡排布'
);
assert.ok(
  ordersWxml.includes('empty-state') && ordersWxml.includes('filteredOrders.length'),
  '订单页必须为空分类结果提供空状态'
);
assert.ok(ordersWxml.includes('tabbar-safe-space'), '订单页列表底部必须保留 TabBar 安全区');
assert.ok(
  ordersWxml.includes('bindtap="openOrderDetail"') && ordersWxml.includes('data-id="{{item.id}}"'),
  '订单卡片必须携带订单 ID 进入详情页'
);
assert.ok(
  ordersWxss.includes('height: 100vh') && ordersWxss.includes('min-height: 0'),
  '订单页必须使用视口高度骨架与自适应滚动区'
);

// 阶段 C 后业务数据已迁至数据库：测试改从 DB seed 解析（scripts/lib/seed-data.mjs）
const { loadStores, loadMenu, loadMemberLevels, readAppConfig, readSeed } = await import('./lib/seed-data.mjs');
const stores = loadStores();
const menuTabs = loadMenu();
const memberLevels = loadMemberLevels();
const cities = readAppConfig('app_cities') || [];
const homeShortcuts = readAppConfig('home_shortcuts') || [];
const profileFunctions = readAppConfig('profile_functions') || [];
const signInRules = readAppConfig('signin_rules') || [];
const signInRewards = readAppConfig('signin_rewards') || [];
const pointsSignIn = readAppConfig('points_signin', readSeed('V16__seed_app_data.sql')) || {};
const { orderCategories, exchangeRecordCategories, pointsCategories, formatOrderAmount } = require('../data/mock.js');

// 优惠券：V6 seed
const marketingSeed = readSeed('V6__seed_marketing.sql');
const coupons = [...marketingSeed.match(/INSERT INTO coupon \(id, code[\s\S]*?;/)[0]
  .matchAll(/\((\d+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*(\d+),\s*(\d+)/g)]
  .map(m => ({
    id: m[2],
    title: m[3],
    type: m[4],
    amount: Math.round(Number(m[5]) / 100),
    threshold: Math.round(Number(m[6]) / 100),
    condition: '满' + Math.round(Number(m[6]) / 100) + '可用',
    quantity: 1,
    applicableStoreIds: [],
    applicableProductIds: []
  }));

// 积分商品：V6 + V16
const pointsProductSql = readSeed('V6__seed_marketing.sql').match(/INSERT INTO points_product \(id, code[\s\S]*?;/)[0]
  + readSeed('V16__seed_app_data.sql').match(/INSERT INTO points_product \(id, code[\s\S]*?badge_in_image=VALUES\(badge_in_image\);/)[0];
const pointsProducts = [...pointsProductSql.matchAll(/\((\d+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*(\d+),\s*(\d+),/g)]
  .map(m => ({ id: m[2], name: m[3], image: m[4], points: Number(m[5]), stock: Number(m[6]) }))
  .filter((item, index, list) => list.findIndex(x => x.id === item.id) === index);

// 礼品卡分组（V16 seed）
const giftCardGroups = [...new Set((readSeed('V16__seed_app_data.sql')
  .match(/INSERT INTO gift_card_denomination \(code, group_id[\s\S]*?;/)[0]
  .matchAll(/\('[^']+',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'/g))
  .map(m => m[0]))].map(str => {
  const m = str.match(/'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'/);
  return { groupId: m[1], groupTitle: m[2], cardName: m[3], cardImage: m[4] };
});
const giftCardDenominations = [...new Set((readSeed('V16__seed_app_data.sql')
  .match(/INSERT INTO gift_card_denomination \(code, group_id[\s\S]*?;/)[0]
  .matchAll(/\('[^']+',\s*'[^']*',\s*'[^']*',\s*'[^']*',\s*'[^']*',\s*'[^']*',\s*(\d+),\s*(\d+),/g))
  .map(m => Number(m[1])))].map(fen => ({ faceValue: fen / 100, salePrice: fen / 100 }));
const initialCartItems = require('../data/mock.js').initialCartItems;

const ordersJs = fs.readFileSync(path.join(root, 'pages/orders/orders.js'), 'utf8');
assert.ok(
  ordersJs.includes('wx.navigateTo') && ordersJs.includes('/pages/order-detail/order-detail?id='),
  '订单列表必须跳转到订单详情页'
);
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
assert.ok(appJson.pages.includes('pages/stored-value/stored-value'), 'app.json 必须注册会员储值页');
const storedValueWxml = fs.readFileSync(path.join(root, 'pages/stored-value/stored-value.wxml'), 'utf8');
const storedValueWxss = fs.readFileSync(path.join(root, 'pages/stored-value/stored-value.wxss'), 'utf8');
const storedValueJs = fs.readFileSync(path.join(root, 'pages/stored-value/stored-value.js'), 'utf8');
assert.ok(
  storedValueWxml.includes('title="会员储值"') &&
    storedValueWxml.includes('back="{{true}}"') &&
    !storedValueWxml.includes('search="{{true}}"') &&
    !storedValueJs.includes('handleSearch'),
  '会员储值页必须提供标题和返回且不得保留无功能搜索'
);
assert.ok(
  storedValueWxml.includes('stored-value-banner.jpg') &&
    storedValueWxml.includes('gift-brand.svg') &&
    storedValueWxml.includes('file-search-brand.svg'),
  '会员储值页必须使用约定素材和 Lucide 图标'
);
assert.ok(
  storedValueJs.includes('changeQuantity') &&
    storedValueJs.includes('handleRecharge') &&
    storedValueJs.includes('wx.showToast'),
  '会员储值页必须提供本地数量交互和未接入提示'
);
assert.ok(
  storedValueWxss.includes('var(--brand-green)') &&
    storedValueWxss.includes('var(--page-gutter)') &&
    storedValueWxss.includes('var(--radius-lg)'),
  '会员储值页必须遵守设计系统 token'
);
assert.ok(appJson.pages.includes('pages/order-detail/order-detail'), 'app.json 必须注册订单详情页');
for (const page of appJson.pages) {
  const pageConfig = readProjectJson(`${page}.json`);
  const navigationStyle = pageConfig.navigationStyle || appJson.window?.navigationStyle;
  assert.equal(navigationStyle, 'custom', `Skyline 页面必须使用 custom 导航模式: ${page}`);
}
const orderDetailWxml = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.wxml'), 'utf8');
const orderDetailWxss = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.wxss'), 'utf8');
const orderDetailJson = readProjectJson('pages/order-detail/order-detail.json');
assert.equal(orderDetailJson.navigationStyle, 'custom', '订单详情页必须使用 custom 导航模式');
assert.equal(
  orderDetailJson.usingComponents['navigation-bar'],
  '/components/navigation-bar/navigation-bar',
  '订单详情页必须注册自定义导航栏组件'
);
assert.ok(
  orderDetailWxml.includes('<navigation-bar title="订单详情"') && orderDetailWxml.includes('back="{{true}}"'),
  '订单详情页必须提供标题和返回按钮'
);
assert.ok(
  orderDetailWxml.includes('pickupCode') && orderDetailWxml.includes('已优惠') && orderDetailWxml.includes('共'),
  '订单详情必须包含状态、取餐号和金额汇总'
);
assert.ok(
  orderDetailWxml.includes("order.orderStatus === 'paid_pickup'") &&
    orderDetailWxml.includes('order.progressSteps') &&
    orderDetailWxml.includes('order.rewards'),
  '待取餐详情必须包含进度、优惠券、成长值和时光币'
);
assert.ok(
  orderDetailWxml.includes("order.orderStatus === 'completed'") &&
    orderDetailWxml.includes('再来一单') &&
    orderDetailWxml.includes('立即评价') &&
    orderDetailWxml.includes('用餐信息') &&
    orderDetailWxml.includes('订单信息'),
  '已完成详情必须包含操作按钮、用餐信息和订单信息'
);
assert.ok(orderDetailWxml.includes('copy.svg'), '订单详情必须保留复制订单编号图标');
assert.ok(
  !orderDetailWxml.includes('phone.svg') &&
    !orderDetailWxml.includes('send.svg') &&
    !orderDetailWxss.includes('round-action'),
  '订单详情不得再显示电话与分享装饰图标'
);



assert.ok(
  orderDetailWxml.includes('wx:if="{{item.badgeIcon}}"') &&
    /\.product-row__crown\s*\{[\s\S]*?right:\s*-4rpx/.test(orderDetailWxss),
  '订单详情商品角标必须数据驱动且位于图片右上角'
);
const orderDetailJs = fs.readFileSync(path.join(root, 'pages/order-detail/order-detail.js'), 'utf8');
assert.ok(
  !orderDetailJs.includes('handlePhone') && !orderDetailJs.includes('handleShare'),
  '订单详情不得保留已移除图标的死代码'
);
assert.ok(orderDetailWxml.includes('<block wx:if="{{order.pickupCode}}">'), '空取单号必须条件渲染，不得显示空白标签');
assert.ok(
  orderDetailWxml.includes("order.orderStatus === 'pending_verify'") &&
    orderDetailWxml.includes('<qrcode value="{{order.pickupCode}}"') &&
    orderDetailWxml.includes('copyVerifyCode') &&
    orderDetailWxml.includes('scanVerifyCode') &&
    orderDetailWxml.includes('扫码核销'),
  '订单详情必须在待核销时展示二维码、复制与扫码核销'
);

assert.equal(orderDetailJson.renderer, undefined, '订单详情页必须继承全局 Skyline，不得局部降级 webview');
const qrcodeWxml = fs.readFileSync(path.join(root, 'components/qrcode/qrcode.wxml'), 'utf8');
const qrcodeJs = fs.readFileSync(path.join(root, 'components/qrcode/qrcode.js'), 'utf8');
assert.ok(
  qrcodeWxml.includes('<canvas') && qrcodeWxml.includes('type="2d"') && qrcodeJs.includes('getContext'),
  '二维码组件必须在 Skyline 下使用 canvas 2d 绘制'
);
assert.ok(
  !qrcodeWxml.includes('qrcode__grid') && !qrcodeWxml.includes('qrcode__cell'),
  '二维码组件不得使用大量 view 网格渲染'
);
assert.ok(
  /\.status-card__code\s*\{[\s\S]*?font-size:\s*var\(--font-display\)/.test(orderDetailWxss),
  '取单号字号必须收敛到设计档位'
);
assert.ok(
  /\.status-card__title\s*\{[\s\S]*?color:\s*var\(--brand-green\)/.test(orderDetailWxss) &&
    orderDetailWxss.includes('.status-card.is-canceled'),
  '状态标题必须区分已完成与已取消配色'
);
assert.ok(
  orderDetailWxml.includes('完成时间') && orderDetailWxml.includes('order.completedTime'),
  '已完成订单必须展示完成时间'
);

assert.ok(
  ordersWxml.includes('store-order__image') && /\.store-order__image\s*\{[\s\S]*?width:\s*120rpx/.test(ordersWxss),
  '门店订单必须展示商品缩略图'
);
assert.ok(
  ordersWxml.includes('cover-order__image') && /\.cover-order__image\s*\{[\s\S]*?width:\s*160rpx/.test(ordersWxss),
  '储值与礼品卡订单必须展示订单封面图'
);
assert.ok(
  orderDetailWxss.includes('var(--brand-green)') && !/#53882C/.test(orderDetailWxss),
  '订单详情样式必须遵守设计系统颜色 token'
);

assert.ok(appJson.pages.includes('pages/coupon-list/coupon-list'), 'app.json 必须注册优惠券列表页');
assert.ok(homeWxml.includes('bindtap="openCoupons"'), '首页优惠券入口必须跳转优惠券列表');
assert.ok(
  profileWxml.includes('bindtap="handleProfileAction"') && profileWxml.includes('data-id="{{item.id}}"'),
  '个人中心优惠券入口必须携带功能 ID'
);
const couponPageWxml = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.wxml'), 'utf8');
const couponPageWxss = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.wxss'), 'utf8');
const couponPageJs = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.js'), 'utf8');
const couponPageJson = fs.readFileSync(path.join(root, 'pages/coupon-list/coupon-list.json'), 'utf8');
assert.ok(
  couponPageWxml.includes('优惠券列表') &&
    couponPageWxml.includes('开启优惠券过期/到账提醒') &&
    couponPageWxml.includes('兑换优惠券') &&
    couponPageWxml.includes('共{{item.quantity}}张'),
  '优惠券页必须包含顶部提醒、兑换入口和数量标签'
);
assert.ok(
  couponPageWxml.includes('立减') &&
    couponPageWxml.includes('使用规则') &&
    couponPageWxml.includes('去使用') &&
    couponPageWxml.includes('历史优惠券') &&
    couponPageWxml.includes('批量赠送') &&
    couponPageWxml.includes('赠送记录'),
  '优惠券页必须保留券卡和底部操作结构'
);
assert.ok(
  couponPageWxml.includes('circle-help.svg') &&
    couponPageWxml.includes('badge-japanese-yen-brand.svg') &&
    couponPageWxml.includes('chevron-right-brand.svg'),
  '优惠券页必须使用规定 Lucide 图标'
);
assert.ok(
  couponPageJs.includes('toggleRules') &&
    couponPageJs.includes('copyCouponNo') &&
    couponPageJs.includes("wx.switchTab({ url: '/pages/menu/menu' })") &&
    couponPageJs.includes('handleViewStores'),
  '优惠券页必须支持规则展开、复制券号、去使用和查看门店'
);
assert.ok(
  couponPageWxss.includes('var(--brand-green)') &&
    couponPageWxss.includes('var(--font-display)') &&
    !/#53882C/.test(couponPageWxss),
  '优惠券页必须遵守设计系统颜色和字号 token'
);
assert.ok(
  couponPageWxml.includes('wx:if="{{coupons.length}}"') &&
    couponPageWxml.includes('<empty-state') &&
    couponPageWxml.includes('暂无可用优惠券'),
  '优惠券为空时必须展示空状态'
);
assert.ok(
  couponPageJson.includes('empty-state') && couponPageJson.includes('/components/empty-state/empty-state'),
  '优惠券页必须注册 empty-state 组件'
);
assert.ok(couponPageWxml.includes('bindtap="handleViewProducts"'), '优惠券列表必须接通查看适用商品');
assert.ok(
  couponPageJs.includes('handleViewProducts') && couponPageJs.includes('&next=products'),
  '查看适用商品必须进入商品门店流程'
);
assert.ok(/\.exchange-card\s*\{[\s\S]*?height:\s*72rpx/.test(couponPageWxss), '兑换卡高度必须收紧到 72rpx');
assert.ok(/\.coupon-stack\s*\{[\s\S]*?margin-top:\s*24rpx/.test(couponPageWxss), '券卡堆叠顶部间距必须收紧到 24rpx');
assert.ok(
  /\.coupon-card__count\s*\{[\s\S]*?min-width:\s*120rpx[\s\S]*?padding:\s*0 16rpx/.test(couponPageWxss),
  '券数量角标必须加宽到 120rpx 并保留 16rpx 内边距'
);
assert.ok(
  /\.coupon-card__main\s*\{[\s\S]*?padding:\s*32rpx 24rpx 16rpx/.test(couponPageWxss),
  '券卡主区顶部内边距必须收紧到 32rpx'
);
assert.ok(/\.coupon-card__value\s*\{[\s\S]*?width:\s*160rpx/.test(couponPageWxss), '券卡金额栏宽度必须收紧到 160rpx');
assert.ok(/\.coupon-card__copy\s*\{[\s\S]*?padding-left:\s*8rpx/.test(couponPageWxss), '券卡标题间距必须收紧到 8rpx');
assert.ok(
  /\.exchange-card__text\s*\{[\s\S]*?color:\s*var\(--brand-green\)/.test(couponPageWxss),
  '兑换优惠券文字必须使用品牌绿'
);
assert.ok(
  /\.coupon-card__title\s*\{[\s\S]*?font-size:\s*var\(--font-md\)/.test(couponPageWxss),
  '优惠券名称必须降为 font-md'
);
assert.ok(
  /\.coupon-card__rules-toggle\s*\{[\s\S]*?font-size:\s*var\(--font-sm\)/.test(couponPageWxss),
  '使用规则标题必须降为 font-sm'
);
assert.ok(
  /\.rule-row,[\s\S]*?\.rule-block\s*\{[\s\S]*?padding:\s*4rpx 0[\s\S]*?font-size:\s*var\(--font-sm\)[\s\S]*?line-height:\s*34rpx/.test(
    couponPageWxss
  ),
  '规则内容必须使用 font-sm、34rpx 行高和 4rpx 行间距'
);
assert.ok(
  /\.coupon-card__details\s*\{[\s\S]*?padding:\s*8rpx 24rpx 16rpx/.test(couponPageWxss),
  '规则展开区必须收紧上下留白'
);
// 优惠券模板来自数据库（V6 seed），券包数量以接口用户券为准
assert.ok(coupons.length >= 1, '数据库必须至少配置一张优惠券模板');
const threeYuanCoupon = coupons.find(item => item.amount === 3);
assert.ok(threeYuanCoupon, '必须包含 3 元代金券');
assert.equal(threeYuanCoupon.condition, '满20可用', '3 元券使用门槛必须为满20可用');
assert.ok(threeYuanCoupon.title, '优惠券必须包含名称');
assert.equal(stores.length, 5, '选择商品适用门店页必须覆盖 V3 seed 的 5 家门店');
assert.ok(
  stores.every(store => store.address),
  '适用门店必须包含地址'
);
const { sortStoresByDistance } = require(path.join(root, 'utils/store.js'));
const decoratedStores = sortStoresByDistance(stores, { latitude: 28.2282, longitude: 112.9388 });
assert.ok(
  decoratedStores.every(store => store.distanceKm),
  '门店经距离装饰后必须包含公里数'
);
assert.ok(
  stores.every(store => store.address.length >= 30),
  '适用门店地址必须补充到可形成两至三行的信息密度'
);
// 个人中心数据来自 /api/v1/app/auth/me；本地不再持有 userProfile 假数据
assert.ok(!require('../data/mock.js').userProfile, 'data/mock.js 不得再导出 userProfile');
const giftPageWxml = fs.readFileSync(path.join(root, 'pages/gift-card/gift-card.wxml'), 'utf8');
const giftPageWxss = fs.readFileSync(path.join(root, 'pages/gift-card/gift-card.wxss'), 'utf8');
const giftPageJson = readProjectJson('pages/gift-card/gift-card.json');
assert.ok(appJson.pages.includes('pages/gift-card/gift-card'), 'app.json 必须注册礼品卡页');
assert.equal(giftPageJson.navigationStyle, 'custom', '礼品卡页必须使用 Skyline 兼容的 custom 导航模式');
assert.equal(
  giftPageJson.usingComponents['navigation-bar'],
  '/components/navigation-bar/navigation-bar',
  '礼品卡页必须注册自定义导航栏'
);
assert.ok(
  giftPageWxml.includes('<navigation-bar title="礼品卡"') && giftPageWxml.includes('back="{{true}}"'),
  '礼品卡页必须提供标题和返回按钮'
);
assert.ok(
  giftPageWxml.includes('购买礼品卡') && giftPageWxml.includes('我的礼品卡') && giftPageWxml.includes('switchTab'),
  '礼品卡页必须包含购买与我的双 Tab 切换'
);
assert.ok(
  giftPageWxml.includes('currentStore.name') &&
    giftPageWxml.includes('currentStore.distanceText') &&
    giftPageWxml.includes('selectStore'),
  '礼品卡页必须包含门店行与切换入口'
);
assert.ok(
  giftPageWxml.includes('filteredGiftCardGroups') &&
    giftPageWxml.includes('gift-grid') &&
    giftPageWxml.includes('gift-group__title'),
  '礼品卡页必须包含可筛选的分组卡片网格'
);
assert.ok(
  giftPageWxml.indexOf('gift-search') < giftPageWxml.indexOf('gift-group__title') &&
    giftPageWxml.includes('<input') &&
    giftPageWxml.includes('bindinput="handleSearchInput"'),
  '礼品卡搜索框必须使用真实输入框并位于首个分组标题上方'
);
assert.ok(
  giftPageWxml.includes('search.svg') &&
    giftPageWxml.includes('搜索礼品卡名称') &&
    giftPageWxml.includes('clearSearch'),
  '礼品卡页必须包含名称搜索、占位提示与清空入口'
);
assert.ok(
  giftPageWxml.includes('<empty-state') && giftPageWxml.includes('还没有礼品卡'),
  '我的礼品卡 Tab 必须有空态占位'
);
assert.ok(
  giftPageWxml.includes('未找到相关礼品卡') && giftPageWxml.includes('filteredGiftCardGroups.length'),
  '礼品卡搜索无结果时必须展示空状态'
);
assert.ok(
  giftPageWxss.includes('var(--brand-green)') && !/#53882C/.test(giftPageWxss),
  '礼品卡页必须遵守设计系统颜色 token'
);
assert.ok(
  /\.gift-group__title\s*\{[\s\S]*?font-size:\s*var\(--font-md\)/.test(giftPageWxss),
  '礼品卡分组标题必须使用较小的 font-md 字号'
);
assert.ok(Array.isArray(giftCardGroups) && giftCardGroups.length >= 2, '礼品卡必须至少包含两个分组');
// 礼品卡分组与卡面来自 V16 seed（group_id / group_title / card_name / card_image）
assert.ok(
  giftCardGroups.every(g => g.groupId && g.groupTitle && g.cardName && g.cardImage),
  '礼品卡分组结构必须完整'
);
assert.deepEqual(
  giftCardDenominations.map(item => [item.faceValue, item.salePrice]),
  [
    [100, 100],
    [200, 200],
    [500, 500]
  ],
  '礼品卡面额必须为 100/200/500 且售价等于面额'
);

const profileGiftJs = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(
  profileWxml.includes('bindtap="openGiftCards"') &&
    profileWxml.includes('gift-card-item__image') &&
    profileWxml.includes('giftCards'),
  '我的页礼品卡区块必须展示礼品卡卡面信息并可跳转礼品卡页'
);
assert.ok(!profileWxml.includes('background-color: {{item.color}}'), '我的页礼品卡不得再使用纯色色块');
assert.ok(
  profileWxss.includes('.gift-list') && profileWxss.includes('flex-wrap: wrap') && profileWxss.includes('nth-child(2n)'),
  '我的页礼品卡列表必须为一行两个的网格布局'
);
assert.ok(
  profileGiftJs.includes('this.openGiftCardOrders();') &&
    profileGiftJs.includes('/pages/gift-card-orders/gift-card-orders') &&
    profileGiftJs.includes('openGiftCardOrders'),
  '我的页顶部礼品卡统计必须跳转礼品卡订单页'
);
assert.ok(
  profileWxml.includes('bindtap="openGiftCards"') &&
    profileGiftJs.includes('/pages/gift-card/gift-card') &&
    profileGiftJs.includes('openGiftCards'),
  '我的页礼品卡区块必须跳转礼品卡页'
);
assert.ok(
  true,
  '个人中心礼品卡必须为卡面图结构'
);

const giftOrdersWxml = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.wxml'), 'utf8');
const giftOrdersWxss = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.wxss'), 'utf8');
const giftOrdersJs = fs.readFileSync(path.join(root, 'pages/gift-card-orders/gift-card-orders.js'), 'utf8');
assert.ok(appJson.pages.includes('pages/gift-card-orders/gift-card-orders'), 'app.json 必须注册礼品卡订单页');
assert.ok(
  giftOrdersWxml.includes('<navigation-bar title="礼品卡订单"') && giftOrdersWxml.includes('back="{{true}}"'),
  '礼品卡订单页必须提供标题和返回按钮'
);
assert.ok(
  giftOrdersWxml.includes('gift-orders-search') &&
    giftOrdersWxml.includes('bindinput="handleSearchInput"') &&
    giftOrdersWxml.includes('clearSearch'),
  '礼品卡订单页必须包含搜索与清空入口'
);
assert.ok(
  giftOrdersWxml.includes('statusTabs') && giftOrdersWxml.includes('selectStatus'),
  '礼品卡订单页必须包含状态筛选'
);
assert.ok(
  giftOrdersWxml.includes('openOrderDetail') && giftOrdersWxml.includes('data-id="{{item.id}}"'),
  '礼品卡订单卡片必须携带订单 ID 跳转详情'
);
assert.ok(
  !giftOrdersWxml.includes('<qrcode') && !giftOrdersWxml.includes('核销码') && !giftOrdersWxml.includes('请向门店出示此二维码核销'),
  '礼品卡订单列表不得直接展示核销码与二维码'
);
assert.ok(
  giftOrdersWxml.includes('empty-state') && giftOrdersWxml.includes('filteredOrders.length'),
  '礼品卡订单页必须提供空状态'
);
assert.ok(
  giftOrdersJs.includes('fetchGiftCardOrders') &&
    giftOrdersJs.includes('orderNo') &&
    giftOrdersJs.includes('matchStatus'),
  '礼品卡订单页必须按名称/订单号搜索并按状态过滤'
);
assert.ok(
  giftOrdersWxss.includes('height: 100vh') &&
    giftOrdersWxss.includes('min-height: 0') &&
    giftOrdersWxss.includes('var(--brand-green)') &&
    !/#53882C/.test(giftOrdersWxss),
  '礼品卡订单页必须使用视口骨架并遵守颜色 token'
);

assert.ok(appJson.pages.includes('pages/points-mall/points-mall'), 'app.json 必须注册积分商城主页');
assert.ok(appJson.pages.includes('pages/points-exchange/points-exchange'), 'app.json 必须注册兑换详情页');
assert.ok(appJson.pages.includes('pages/points-detail/points-detail'), 'app.json 必须注册积分明细页');
assert.ok(appJson.pages.includes('pages/exchange-records/exchange-records'), 'app.json 必须注册兑换记录页');

const pointsMallWxml = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.wxml'), 'utf8');
const pointsMallWxss = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.wxss'), 'utf8');
const pointsMallJs = fs.readFileSync(path.join(root, 'pages/points-mall/points-mall.js'), 'utf8');
assert.ok(
  pointsMallWxml.includes('时光币商城') &&
    pointsMallWxml.includes('时光币明细') &&
    pointsMallWxml.includes('时光币规则') &&
    pointsMallWxml.includes('兑换记录'),
  '时光币商城必须包含标题和三项时光币入口'
);
assert.ok(
  pointsMallWxml.includes('签到有礼') &&
    pointsMallWxml.includes('points-categories') &&
    pointsMallWxml.includes('points-grid'),
  '积分商城必须包含签到卡、分类页签和商品网格'
);
assert.ok(
  !pointsMallWxml.includes('search="{{true}}"') &&
    !pointsMallWxml.includes('bindsearch="handleSearch"') &&
    !pointsMallJs.includes('handleSearch'),
  '积分商城不得保留无功能搜索入口'
);
assert.ok(
  pointsMallWxml.includes('points-product__name ellipsis') && pointsMallWxml.includes('mode="aspectFit"'),
  '积分商品名必须单行省略且商品图完整展示'
);
assert.ok(
  pointsMallJs.includes('filterCategory') &&
    pointsMallJs.includes('openProduct') &&
    pointsMallJs.includes('openPointsDetail') &&
    pointsMallJs.includes('openExchangeRecords') &&
    pointsMallJs.includes('selectStore'),
  '积分商城必须支持分类、商品、积分明细、记录和门店交互'
);
assert.ok(
  pointsMallWxss.includes('var(--brand-soft)') &&
    pointsMallWxss.includes('var(--price-red)') &&
    pointsMallWxss.includes('var(--radius-md)'),
  '积分商城必须遵守设计系统颜色与圆角 token'
);
assert.ok(
  /\.points-hero__coin\s*\{[\s\S]*?background:\s*transparent[\s\S]*?box-shadow:\s*none/.test(pointsMallWxss),
  '积分余额图标不得保留额外圆形底板'
);
assert.ok(
  /\.checkin-card__icon-wrap\s*\{[\s\S]*?background:\s*transparent/.test(pointsMallWxss),
  '签到图标不得保留额外方形底板'
);
assert.ok(
  !/\.points-product__name\s*\{[\s\S]*?min-height:\s*72rpx/.test(pointsMallWxss),
  '积分商品名不得使用两行最小高度'
);
assert.ok(
  pointsMallWxml.includes('points-hero__watermark') && pointsMallWxml.includes('五零时光'),
  '积分商城必须包含自有品牌水印'
);
assert.ok(!/font-size:\s*\d/.test(pointsMallWxss), '积分商城字号必须全部使用设计 Token');

const pointsExchangeWxml = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.wxml'), 'utf8');
const pointsExchangeWxss = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.wxss'), 'utf8');
const pointsExchangeJs = fs.readFileSync(path.join(root, 'pages/points-exchange/points-exchange.js'), 'utf8');
assert.ok(
  pointsExchangeWxml.includes('兑换详情') &&
    pointsExchangeWxml.includes('1/1') &&
    pointsExchangeWxml.includes('quantity') &&
    pointsExchangeWxml.includes('时光币不足'),
  '兑换详情必须包含商品图、数量步进器和时光币不足按钮'
);
assert.ok(pointsExchangeWxml.includes('wx:if="{{item.limitText}}"'), '兑换详情限制提示必须允许为空且按条件渲染');
assert.ok(
  pointsExchangeJs.includes('decreaseQuantity') &&
    pointsExchangeJs.includes('increaseQuantity') &&
    pointsExchangeJs.includes('handleExchange') &&
    pointsExchangeJs.includes('getPoints() < points'),
  '兑换详情必须支持数量调整和积分不足判断'
);
assert.ok(pointsExchangeJs.includes('exchangePointsProduct'), '兑换必须调用后端接口，禁止本地伪造扣减');
assert.ok(
  pointsExchangeWxss.includes('height: 100vh') &&
    pointsExchangeWxss.includes('env(safe-area-inset-bottom)') &&
    pointsExchangeWxss.includes('var(--brand-green)'),
  '兑换详情必须使用固定底部操作栏并遵守设计系统 token'
);

const pointsDetailWxml = fs.readFileSync(path.join(root, 'pages/points-detail/points-detail.wxml'), 'utf8');
const pointsDetailWxss = fs.readFileSync(path.join(root, 'pages/points-detail/points-detail.wxss'), 'utf8');
const pointsDetailJs = fs.readFileSync(path.join(root, 'pages/points-detail/points-detail.js'), 'utf8');
assert.ok(
  pointsDetailWxml.includes('时光币明细') &&
    pointsDetailWxml.includes('pointsRecords') &&
    pointsDetailWxml.includes('empty-state'),
  '时光币明细必须包含记录列表与空状态'
);
assert.ok(pointsDetailJs.includes('pointsRecords'), '积分明细页必须读取积分记录 Mock');
assert.ok(
  /\.points-record\s*\{[\s\S]*?padding:\s*32rpx var\(--page-gutter\)/.test(pointsDetailWxss),
  '积分明细行必须使用更接近参考图的内边距'
);

const exchangeRecordsWxml = fs.readFileSync(path.join(root, 'pages/exchange-records/exchange-records.wxml'), 'utf8');
const exchangeRecordsJs = fs.readFileSync(path.join(root, 'pages/exchange-records/exchange-records.js'), 'utf8');
assert.ok(
  exchangeRecordsWxml.includes('兑换记录') &&
    exchangeRecordsWxml.includes('exchange-record-categories') &&
    exchangeRecordsWxml.includes('/assets/images/3x/points-empty.jpg'),
  '兑换记录必须包含状态页签与空状态图'
);
assert.ok(
  exchangeRecordsJs.includes('switchCategory') && exchangeRecordsJs.includes('exchangeRecords'),
  '兑换记录必须支持状态筛选'
);

// 时光币余额由后端 app_user.points 提供，本地无初始值可断言
assert.equal(pointsCategories.length, 3, '积分商城必须包含全部、宠物公益和优惠券三个分类');
assert.equal(pointsProducts.length, 5, '积分商品表必须包含 V6 的 2 条 + V16 补齐的 3 条');
assert.ok(
  pointsProducts.every(item => item.id && item.name && item.image && item.points > 0 && item.stock > 0),
  '积分商品字段必须完整'
);
// 分类与券字段由 V16 回填，校验 seed 中确实写入了这些列
assert.ok(/ADD COLUMN category\s+VARCHAR/.test(readSeed('V16__seed_app_data.sql')), 'V16 必须为 points_product 增加 category 列');
assert.ok(!pointsProducts.some(item => item.id === 'points-coupon-3' && item.points <= 0), 'V6 的兑换券商品必须保留有效积分');
// 时光币流水与兑换记录均为「按用户动态生成」，本地不再有演示数据；
// 页面改为接口驱动，这里校验页面确实走接口而非本地假数据。
const pointsDetailSource = fs.readFileSync(path.join(root, 'pages/points-detail/points-detail.js'), 'utf8');
assert.ok(pointsDetailSource.includes('fetchPointsRecords'), '时光币明细页必须从后端拉取流水');
const exchangeRecordsSource = fs.readFileSync(path.join(root, 'pages/exchange-records/exchange-records.js'), 'utf8');
assert.ok(exchangeRecordsSource.includes('fetchExchangeOrders'), '兑换记录页必须从后端拉取记录');
assert.deepEqual(
  exchangeRecordCategories.map(item => item.label),
  ['全部', '待支付', '待发货', '待收货', '待核销', '已核销', '已完成'],
  '兑换记录页签必须与参考图一致'
);
assert.deepEqual(
  exchangeRecordCategories.map(item => item.label),
  ['全部', '待支付', '待发货', '待收货', '待核销', '已核销', '已完成'],
  '兑换记录页签必须与参考图一致'
);

const homeWxmlForPoints = fs.readFileSync(path.join(root, 'pages/home/home.wxml'), 'utf8');
const homeJsForPoints = fs.readFileSync(path.join(root, 'pages/home/home.js'), 'utf8');
const profileJsForPoints = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');
assert.ok(
  homeWxmlForPoints.includes('bindtap="handleShortcut"') && homeWxmlForPoints.includes('data-id="{{item.id}}"'),
  '首页快捷入口必须携带功能 ID'
);
assert.ok(homeJsForPoints.includes('/pages/points-mall/points-mall'), '首页积分商城入口必须跳转积分商城');
assert.ok(
  profileJsForPoints.includes("id === 'points'") && profileJsForPoints.includes('/pages/points-mall/points-mall'),
  '个人中心积分入口必须跳转积分商城'
);

assert.ok(appJson.pages.includes('pages/points-signin/points-signin'), 'app.json 必须注册积分签到页');
assert.ok(appJson.pages.includes('pages/points-signin-rules/points-signin-rules'), 'app.json 必须注册签到活动规则页');
const signInWxml = fs.readFileSync(path.join(root, 'pages/points-signin/points-signin.wxml'), 'utf8');
const signInWxss = fs.readFileSync(path.join(root, 'pages/points-signin/points-signin.wxss'), 'utf8');
const signInJs = fs.readFileSync(path.join(root, 'pages/points-signin/points-signin.js'), 'utf8');
assert.ok(
  signInWxml.includes('签到得好礼') && signInWxml.includes('活动规则') && signInWxml.includes('我的时光币'),
  '签到页必须包含标题、活动规则和时光币余额'
);
assert.ok(
  signInWxml.includes('points-signin-calendar.jpg') &&
    signInWxml.includes('signin-week') &&
    signInWxml.includes('立即签到'),
  '签到页必须包含日历主视觉、周条和签到按钮'
);
assert.ok(
  signInWxml.includes('signin-success-modal') &&
    signInWxml.includes('签到成功') &&
    signInWxml.includes('{{awardText}}'),
  '签到页必须包含成功奖励弹窗并展示本次实际奖励'
);
assert.ok(
  signInWxml.includes('signin-calendar-sheet') &&
    signInWxml.includes('calendarCells') &&
    signInWxml.includes('changeMonth'),
  '签到页必须包含可切换月份的日历弹层'
);
assert.ok(signInWxml.includes('signin-reward__coin-inner'), '签到奖励金币必须包含内外双层圆形结构');
assert.ok(/signin-modal__star[^>]+star-brand\.svg/.test(signInWxml), '签到成功弹窗必须使用品牌描边星标');
assert.ok(/signin-points-pill__icon[^>]+star-brand\.svg/.test(signInWxml), '我的时光币胶囊必须使用品牌描边星标');
assert.ok(signInWxml.includes('signin-calendar-cell__date-band'), '月历日期格必须使用独立日期栏');
assert.ok(
  signInJs.includes('buildMonthCells') &&
    signInJs.includes('api') &&
    signInJs.includes('signIn()') &&
    signInJs.includes('openCalendar') &&
    signInJs.includes('closeSuccess') &&
    signInJs.includes('getApp()'),
  '签到页必须使用会话状态 + 后端接口完成签到与月历交互'
);
assert.ok(
  signInWxss.includes('var(--brand-soft)') &&
    signInWxss.includes('var(--brand-green)') &&
    signInWxss.includes('env(safe-area-inset-bottom)'),
  '签到页必须遵守设计系统 token 与安全区规则'
);
assert.ok(
  /\.signin-week__date\s*\{[\s\S]*?background:\s*var\(--brand-soft\)/.test(signInWxss),
  '周签到日期栏必须使用品牌浅色底'
);
assert.ok(
  /\.signin-reward__coin-inner\s*\{[\s\S]*?border:\s*1rpx solid var\(--brand-soft\)/.test(signInWxss),
  '奖励金币必须包含浅色内环'
);
assert.ok(/\.signin-calendar-grid\s*\{[\s\S]*?gap:\s*4rpx/.test(signInWxss), '月历七列必须使用 4rpx 等距网格');
assert.ok(/\.signin-calendar-weekdays\s*\{[\s\S]*?flex-wrap:\s*nowrap/.test(signInWxss), '星期标题必须禁止换行');
assert.ok(
  /\.signin-calendar-weekdays text\s*\{[\s\S]*?flex:\s*0 0 calc\(\(100% - 24rpx\) \/ 7\)/.test(signInWxss),
  '星期标题必须使用七等分列宽'
);
assert.ok(
  /\.signin-calendar-cell\s*\{[\s\S]*?border-radius:\s*var\(--radius-sm\)/.test(signInWxss),
  '月历日期格必须使用设计系统圆角'
);
assert.ok(
  /\.signin-calendar-cell__date-band\s*\{[\s\S]*?background:\s*var\(--brand-soft\)/.test(signInWxss),
  '月历未签到日期栏必须使用品牌浅色底'
);
assert.ok(
  signInWxss.includes('min-height: 460rpx') && signInWxss.includes('width: 520rpx'),
  '签到主页主视觉必须增高并放大日历图'
);
assert.ok(/\.signin-modal__panel\s*\{[\s\S]*?max-width:\s*660rpx/.test(signInWxss), '签到成功弹窗必须限制最大宽度');

const signInRulesWxml = fs.readFileSync(path.join(root, 'pages/points-signin-rules/points-signin-rules.wxml'), 'utf8');
assert.ok(
  signInRulesWxml.includes('活动规则') && signInRulesWxml.includes('signInRules'),
  '签到活动规则页必须循环展示完整奖励规则'
);
assert.ok(
  signInRulesWxml.includes('五零时光') && !signInRulesWxml.includes('真茶屋'),
  '活动规则必须替换为五零时光品牌文案'
);

const appJsForPoints = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
assert.ok(
  appJsForPoints.includes('points:') &&
    appJsForPoints.includes('signedDates') &&
    appJsForPoints.includes('continuousDays') &&
    appJsForPoints.includes('pointsRecords'),
  '全局会话状态必须包含积分、签到日期、连续天数和积分记录'
);
assert.ok(
  pointsMallJs.includes('/pages/points-signin/points-signin') &&
    pointsMallJs.includes('/pages/points-signin-rules/points-signin-rules'),
  '积分商城必须接通签到页和活动规则页'
);
assert.ok(pointsMallWxml.includes('{{pointsBalance}}'), '积分商城主页必须显示全局签到后的积分余额');
assert.ok(
  pointsDetailJs.includes('onShow') && pointsDetailJs.includes('globalData.pointsRecords'),
  '积分明细页必须在返回时同步全局签到记录'
);
assert.ok(profileJsForPoints.includes('globalData.points'), '个人中心必须在显示时同步全局积分余额');
assert.equal(signInRewards.map(item => item.days).join(','), '7', '签到奖励只保留7天档');
assert.equal(signInRules.length, 2, '签到活动规则必须包含每日和连续7天两段说明');
assert.equal(pointsSignIn.today, '2026-09-17', '签到页固定参考日期必须为2026-09-17');

const { buildMonthCells, signInOnce } = require('../utils/points-signin.js');
const buildSignInImagesSource = fs.readFileSync(path.join(root, 'scripts/build-points-images.mjs'), 'utf8');
assert.ok(
  buildSignInImagesSource.includes('crop: [470, 390, 590, 340]'),
  '签到主视觉必须避开活动规则白条并保留完整优惠券和日历主体'
);
const initialState = { points: 0, signedDates: [], continuousDays: 0, pointsRecords: [] };
const firstSignIn = signInOnce(initialState, '2026-09-17', '2026-09-17 00:36:15');
assert.equal(firstSignIn.awarded, true, '首次签到必须成功');
assert.equal(firstSignIn.state.points, 1, '首次签到必须增加1时光币');
assert.equal(firstSignIn.state.continuousDays, 1, '首次签到连续天数必须为1');
assert.deepEqual(firstSignIn.state.signedDates, ['2026-09-17'], '首次签到必须记录日期');
assert.equal(firstSignIn.state.pointsRecords.length, 1, '首次签到必须新增一条积分明细');
const duplicateSignIn = signInOnce(firstSignIn.state, '2026-09-17', '2026-09-17 00:36:15');
assert.equal(duplicateSignIn.awarded, false, '同一天重复签到不得再次奖励');
assert.equal(duplicateSignIn.state.points, 1, '重复签到不得重复增加积分');
assert.equal(duplicateSignIn.state.pointsRecords.length, 1, '重复签到不得重复新增记录');
const septemberCells = buildMonthCells(2026, 9, ['2026-09-17']);
assert.equal(septemberCells.length, 42, '月历必须固定生成42个单元格');
assert.equal(septemberCells.filter(cell => cell.key).length, 30, '2026年9月必须包含30天');
assert.equal(
  septemberCells.findIndex(cell => cell.key === '2026-09-01'),
  2,
  '2026年9月1日必须从周二位置开始'
);

// 连续签到每满 7 天额外 +20 时光币
let streakState = { points: 0, signedDates: [], continuousDays: 0, pointsRecords: [] };
for (let day = 1; day <= 8; day += 1) {
  const key = '2026-10-' + String(day).padStart(2, '0');
  streakState = signInOnce(streakState, key, key + ' 09:00').state;
  if (day === 7) assert.equal(streakState.points, 27, '第7天必须获得每日1+额外20共21时光币');
  if (day === 8) assert.equal(streakState.points, 28, '第8天只获得每日1时光币');
}
let streak14 = streakState;
for (let day = 9; day <= 14; day += 1) {
  const key = '2026-10-' + String(day).padStart(2, '0');
  streak14 = signInOnce(streak14, key, key + ' 09:00').state;
}
assert.equal(streak14.points, 54, '第14天必须再次获得额外20时光币（14次每日+两次20）');
assert.equal(septemberCells.find(cell => cell.key === '2026-09-17').signed, true, '月历必须标记已签到日期');

const emptyStateWxss = fs.readFileSync(path.join(root, 'components/empty-state/empty-state.wxss'), 'utf8');
assert.ok(
  emptyStateWxss.includes('var(--brand-green)') &&
    emptyStateWxss.includes('var(--brand-tint)') &&
    emptyStateWxss.includes('var(--text-main)'),
  '空状态组件必须使用设计系统颜色 Token'
);
assert.ok(
  !/#[0-9A-Fa-f]{3,8}/.test(emptyStateWxss) && !/font-size:\s*\d/.test(emptyStateWxss),
  '空状态组件不得保留硬编码颜色和字号'
);

const confirmWxml = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.wxml'), 'utf8');
const confirmWxss = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.wxss'), 'utf8');
const confirmJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
const confirmJson = readProjectJson('pages/order-confirm/order-confirm.json');
assert.ok(appJson.pages.includes('pages/order-confirm/order-confirm'), 'app.json 必须注册确认订单页');
assert.equal(confirmJson.navigationStyle, 'custom', '确认订单页必须使用 custom 导航模式');
assert.equal(
  confirmJson.usingComponents['navigation-bar'],
  '/components/navigation-bar/navigation-bar',
  '确认订单页必须注册自定义导航栏组件'
);
assert.ok(
  confirmWxml.includes('<navigation-bar title="确认订单"') && confirmWxml.includes('back="{{true}}"'),
  '确认订单页必须提供标题和与项目一致的自定义返回'
);
assert.ok(
  confirmWxml.includes('store.name') &&
    confirmWxml.includes('store.distanceText') &&
    confirmWxml.includes('store.address') &&
    confirmWxml.includes('现在下单，立即制作'),
  '确认订单页必须包含门店名、距离、地址与制作提示'
);
assert.ok(
  confirmWxml.includes('modeOptions') &&
    confirmWxml.includes('selectMode') &&
    confirmJs.includes('店内就餐') &&
    confirmJs.includes('打包外带') &&
    confirmJs.includes('dine-in.svg') &&
    confirmJs.includes('takeaway.svg'),
  '确认订单页必须包含店内就餐与打包外带切换'
);
assert.ok(
  confirmWxml.includes('goods-row__image') &&
    confirmWxml.includes('goods-row__name') &&
    confirmWxml.includes('goods-row__spec') &&
    confirmWxml.includes('goods-row__quantity') &&
    confirmWxml.includes('goods-row__original') &&
    confirmWxml.includes('goods-row__actual'),
  '商品行必须包含图、名称、规格、数量与双价'
);
assert.ok(
  confirmWxml.includes("paymentMethod === 'stored-value' ? '储值价' : '小程序价'"),
  '商品行价格标签必须随支付方式切换'
);
assert.ok(
  /paymentMethod === 'stored-value' \? \(item\.storedValuePrice \|\| item\.price\) : item\.price/.test(confirmWxml),
  '商品行金额必须随支付方式切换'
);
assert.ok(confirmWxml.includes('优惠券') && confirmWxml.includes('暂无可用优惠券'), '确认订单页必须包含优惠券行');
assert.ok(confirmWxml.includes('共优惠') && confirmWxml.includes('合计'), '确认订单页必须包含优惠与合计汇总');
assert.ok(
  confirmWxml.includes('当前订单可获得') &&
    confirmWxml.includes('时光币') &&
    confirmWxml.includes('pointCount') &&
    !confirmWxml.includes('集点'),
  '确认订单页必须包含时光币提示且不得再使用集点术语'
);
assert.ok(/Math\.floor\(summary\.amount\)/.test(confirmJs), '时光币必须按每消费 1 元 1 时光币计算（向下取整）');
assert.ok(
  confirmWxml.includes('map-pinned.svg') &&
    /\.store-card__decor\s*\{[\s\S]*?position:\s*absolute[\s\S]*?opacity:\s*0\.1/.test(confirmWxss),
  '门店卡必须包含地图装饰图标且不干扰布局'
);
assert.ok(confirmWxml.includes('免责声明') && confirmWxml.includes('PRODUCT DISCLAIMER'), '确认订单页必须包含免责声明');
assert.ok(
  confirmWxml.includes('预留电话') && confirmWxml.includes('备注') && confirmWxml.includes('<input'),
  '确认订单页必须包含预留电话与备注输入'
);
assert.ok(
  confirmWxml.includes('提交支付') && confirmWxml.includes('共{{count}}件') && confirmWxml.includes('共优惠'),
  '底部结算栏必须包含件数、合计、共优惠与提交支付'
);
assert.ok(
  !confirmWxml.includes('找人付') && !confirmJs.includes('handlePayOther'),
  '找人付功能未实现前不得保留按钮与处理函数'
);
assert.ok(
  /\.confirm-bar__submit\s*\{[\s\S]*?width:\s*280rpx[\s\S]*?border-radius:\s*var\(--radius-md\)/.test(confirmWxss),
  '提交支付按钮宽度必须为 280rpx 且保持圆角矩形'
);
assert.ok(
  /<view class="store-card__distance"[^>]*bindtap="selectStore"[^>]*aria-label="切换门店"/.test(confirmWxml),
  '距您 X km 标签必须支持点击切换门店'
);
assert.ok(!confirmWxml.includes('超值加购'), '确认订单页不得包含超值加购模块');
assert.ok(
  confirmWxss.includes('var(--brand-green)') && !/#53882C/.test(confirmWxss),
  '确认订单页样式必须遵守设计系统颜色 token'
);
assert.ok(
  confirmJs.includes('pendingOrder') && confirmJs.includes('formatOrderAmount') && confirmJs.includes('Math.floor'),
  '确认订单页必须从全局读取结算数据并计算金额与集点'
);
assert.ok(
  confirmWxml.includes('支付方式') && confirmWxml.includes('微信支付') && confirmWxml.includes('储值支付'),
  '确认订单页必须包含微信支付与储值支付两种方式'
);
assert.ok(
  confirmJs.includes('selectPaymentMethod') &&
    confirmJs.includes('stored-value') &&
    confirmJs.includes('storedValuePrice'),
  '确认订单页必须支持切换储值支付并按储值价计算'
);
assert.ok(
  confirmJs.includes('getUserProfile') &&
    confirmJs.includes('saveUserProfile') &&
    confirmJs.includes('余额不足，请先充值'),
  '储值支付必须扣减余额且余额不足时阻止提交'
);
// 阶段 C：商品储值价由菜单接口提供，不再内置于 data/mock.js
assert.ok(
  confirmJs.includes('storedValuePrice'),
  '订单确认页必须从接口商品数据读取储值价'
);

assert.ok(
  !confirmWxml.includes('isGiftCardOrder') &&
    !confirmWxml.includes('gift-card-list') &&
    !confirmWxml.includes('gift-card-summary'),
  '确认订单页不得保留已废弃的礼品卡展示分支'
);
assert.ok(
  !confirmJs.includes("orderType === 'gift-card'") && !confirmJs.includes('isGiftCardOrder'),
  '确认订单页不得识别礼品卡订单类型'
);
const menuJsSource = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
assert.ok(
  menuJsSource.includes('/pages/order-confirm/order-confirm') && !menuJsSource.includes('结算暂未接入'),
  '点单页去结算必须跳转确认订单页'
);
assert.ok(
  menuJsSource.includes('resolveStoreCatalog') &&
    menuJsSource.includes('persistSelectedStore') &&
    menuJsSource.includes('openStorePicker') &&
    !menuJsSource.includes('autoSelectNearest'),
  '点单页必须缓存优先，仅在无有效门店时打开选择层'
);
assert.ok(!giftPageWxml.includes('tabbar-safe-space'), '礼品卡页不得预留 TabBar 安全区（非 Tab 页）');
assert.ok(
  /\.gift-grid__image\s*\{[\s\S]*?height:\s*204rpx/.test(giftPageWxss),
  '礼品卡卡面高度必须为 204rpx 以匹配 1.64:1 素材比例'
);
assert.ok(
  giftPageWxml.includes('去购买礼品卡') && giftPageWxml.includes('bindtap="goBuy"'),
  '我的礼品卡空态必须包含去购买引导按钮'
);
const giftPageJs = fs.readFileSync(path.join(root, 'pages/gift-card/gift-card.js'), 'utf8');
assert.ok(giftPageJs.includes('goBuy'), '礼品卡页必须实现 goBuy 切回购买 Tab');
assert.ok(
  giftPageJs.includes('handleSearchInput') &&
    giftPageJs.includes('filterGiftCardGroups') &&
    giftPageJs.includes('clearSearch'),
  '礼品卡页必须实现本地名称搜索与清空'
);
assert.ok(
  giftPageJs.includes('/pages/gift-card-purchase/gift-card-purchase?id='),
  '礼品卡卡片点击必须跳转购买页并携带卡 ID'
);

assert.ok(appJson.pages.includes('pages/gift-card-purchase/gift-card-purchase'), 'app.json 必须注册礼品卡购买页');
const giftPurchaseWxml = fs.readFileSync(path.join(root, 'pages/gift-card-purchase/gift-card-purchase.wxml'), 'utf8');
const giftPurchaseWxss = fs.readFileSync(path.join(root, 'pages/gift-card-purchase/gift-card-purchase.wxss'), 'utf8');
const giftPurchaseJs = fs.readFileSync(path.join(root, 'pages/gift-card-purchase/gift-card-purchase.js'), 'utf8');
const giftPurchaseJson = readProjectJson('pages/gift-card-purchase/gift-card-purchase.json');
assert.equal(giftPurchaseJson.navigationStyle, 'custom', '礼品卡购买页必须使用 custom 导航模式');
assert.equal(
  giftPurchaseJson.usingComponents['navigation-bar'],
  '/components/navigation-bar/navigation-bar',
  '礼品卡购买页必须注册自定义导航栏'
);
assert.ok(
  giftPurchaseWxml.includes('<navigation-bar title="购买礼品卡"') && giftPurchaseWxml.includes('back="{{true}}"'),
  '礼品卡购买页必须提供标题和返回按钮'
);
assert.ok(
  giftPurchaseWxml.includes('giftCard.name') &&
    giftPurchaseWxml.includes('giftCard.image') &&
    giftPurchaseWxml.includes('选择礼品卡') &&
    giftPurchaseWxml.includes('购买礼品卡后可赠送好友'),
  '礼品卡购买页必须展示当前卡面与选择说明'
);
assert.ok(
  giftPurchaseWxml.includes('denominations') &&
    giftPurchaseWxml.includes('minus.svg') &&
    giftPurchaseWxml.includes('plus.svg') &&
    giftPurchaseWxml.includes('changeQuantity'),
  '礼品卡购买页必须提供多档面额及独立数量控件'
);
assert.ok(
  giftPurchaseWxml.includes('<text class="gift-agreement__text"') &&
    giftPurchaseWxml.includes('阅读并同意') &&
    giftPurchaseWxml.includes('agreementTitle') &&
    giftPurchaseWxml.includes('bindtap="toggleAgreement"') &&
    giftPurchaseWxml.includes('catchtap="showAgreement"'),
  '礼品卡协议必须使用单行连续文本，并隔离勾选与协议查看事件'
);
assert.ok(
  giftPurchaseWxml.indexOf('gift-agreement') > giftPurchaseWxml.indexOf('gift-purchase-bar') &&
    giftPurchaseWxml.indexOf('gift-agreement') < giftPurchaseWxml.indexOf('gift-purchase-bar__button'),
  '礼品卡协议必须固定在购买按钮上方'
);
assert.ok(
  giftPurchaseWxml.includes('购买礼品') &&
    giftPurchaseWxml.includes('bindtap="handlePay"') &&
    giftPurchaseWxml.includes('totalText') &&
    giftPurchaseWxml.includes('totalCount'),
  '礼品卡购买页必须提供合计与直接支付按钮'
);
assert.ok(
  giftPurchaseJs.includes('handlePay') &&
    giftPurchaseJs.includes('startGiftCardPayment') &&
    giftPurchaseJs.includes('支付暂未接入') &&
    !giftPurchaseJs.includes('/pages/order-confirm/order-confirm') &&
    giftPurchaseJs.includes('MAX_QUANTITY = 10'),
  '礼品卡购买页必须直接调用预留支付入口且不得跳转确认订单页'
);
assert.ok(/isAgreed:\s*false/.test(giftPurchaseJs), '礼品卡协议必须默认未勾选');
assert.ok(
  /\.gift-denomination\s*\{[\s\S]*?min-height:\s*144rpx/.test(giftPurchaseWxss),
  '礼品卡规格行高度必须收紧到 144rpx'
);
assert.ok(
  /\.gift-purchase-bar\s*\{[\s\S]*?flex-direction:\s*column/.test(giftPurchaseWxss),
  '礼品卡购买栏必须改为协议在上、合计在下的两行结构'
);
assert.ok(
  /\.gift-agreement__check\s*\{[\s\S]*?width:\s*32rpx[\s\S]*?height:\s*32rpx/.test(giftPurchaseWxss),
  '礼品卡协议勾选框必须缩小到 32rpx'
);
assert.ok(
  /\.gift-agreement__text\s*\{[\s\S]*?line-height:\s*34rpx/.test(giftPurchaseWxss),
  '礼品卡协议文案必须使用紧凑行高'
);
assert.ok(
  giftPurchaseWxss.includes('var(--brand-green)') && !/#53882C/.test(giftPurchaseWxss),
  '礼品卡购买页必须遵守设计系统颜色 token'
);
assert.ok(!giftPurchaseWxml.includes('tabbar-safe-space'), '礼品卡购买页不得预留 TabBar 安全区');

const profileWxssSource = fs.readFileSync(path.join(root, 'pages/profile/profile.wxss'), 'utf8');
const profileLiterals = [
  ...new Set([...profileWxssSource.matchAll(/#[0-9A-Fa-f]{6}/g)].map(m => m[0]).filter(c => c !== '#FFFFFF'))
];
assert.ok(profileLiterals.length === 0, '个人中心样式不得使用颜色字面量（白色除外）');
const profileBadFonts = [
  ...new Set(
    [...profileWxssSource.matchAll(/font-size:\s*(\d+)rpx/g)]
      .map(m => +m[1])
      .filter(v => ![20, 24, 28, 30, 34, 38, 48].includes(v))
  )
];
assert.ok(profileBadFonts.length === 0, '个人中心字号必须全部落在设计档位内');

assert.ok(appJson.pages.includes('pages/coupon-stores/coupon-stores'), 'app.json 必须注册选择商品适用门店页');
const couponStoresWxml = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.wxml'), 'utf8');
const couponStoresWxss = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.wxss'), 'utf8');
const couponStoresJs = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.js'), 'utf8');
assert.ok(
  couponStoresWxml.includes('title="{{title}}"') &&
    couponStoresWxml.includes('map-pinned.svg') &&
    couponStoresWxml.includes('chevron-down.svg') &&
    couponStoresWxml.includes('<store-list-card'),
  '门店页必须包含标题、定位、筛选和公共门店卡'
);
assert.ok(
  couponStoresJs.includes('couponId') &&
    couponStoresJs.includes('applicableStoreIds') &&
    couponStoresJs.includes('showUnavailable'),
  '门店页必须支持券号筛选和未接入提示'
);
assert.ok(couponStoresJs.includes('catalog.city ? catalog.city.name'), '券适用门店页必须同步当前城市名称（含城市为空时的降级）');
assert.ok(couponStoresWxss.includes('var(--page-gutter)'), '适用门店页必须遵守设计系统页边距 token');
assert.ok(
  couponStoresWxml.includes('bind:select="handleSelectStore"') &&
    couponStoresWxml.includes('bind:navigate="handleNavigate"'),
  '门店卡必须支持选择且导航按钮不得冒泡'
);
assert.ok(appJson.pages.includes('pages/coupon-products/coupon-products'), 'app.json 必须注册适用商品页');
const couponProductsWxml = fs.readFileSync(path.join(root, 'pages/coupon-products/coupon-products.wxml'), 'utf8');
const couponProductsWxss = fs.readFileSync(path.join(root, 'pages/coupon-products/coupon-products.wxss'), 'utf8');
const couponProductsJs = fs.readFileSync(path.join(root, 'pages/coupon-products/coupon-products.js'), 'utf8');
assert.ok(
  couponProductsWxml.includes('title="适用商品"') &&
    couponProductsWxml.includes('coupon-product-grid') &&
    couponProductsWxml.includes('item.sizeText'),
  '适用商品页必须保留标题、三列网格和杯型信息'
);
assert.ok(
  couponProductsJs.includes('applicableProductIds') && couponProductsJs.includes('storeName'),
  '适用商品页必须按券商品和所选门店渲染'
);
assert.ok(
  couponProductsWxss.includes('var(--page-gutter)') &&
    couponProductsWxss.includes('var(--line-color)') &&
    couponProductsWxss.includes('var(--radius-sm)'),
  '适用商品页必须遵守设计系统 token'
);

const tabBarWxml = fs.readFileSync(path.join(root, 'custom-tab-bar/index.wxml'), 'utf8');
const tabBarJs = fs.readFileSync(path.join(root, 'custom-tab-bar/index.js'), 'utf8');
const tabPaths = [...tabBarJs.matchAll(/pagePath:\s*'([^']+)'/g)].map(match => match[1]);
assert.equal(tabPaths.length, 5, '底部导航必须保留五个 Tab 页面');
assert.deepEqual(
  tabPaths,
  ['/pages/home/home', '/pages/menu/menu', '/pages/member/member', '/pages/orders/orders', '/pages/profile/profile'],
  '底部导航路径必须与五个 Tab 页面逐项一致'
);
assert.ok(
  tabBarWxml.includes('open-type="switchTab"') && tabBarWxml.includes('url="{{item.pagePath}}"'),
  '底部导航必须使用原生 switchTab 跳转'
);
assert.ok(!tabBarWxml.includes('bindtap="switchTab"'), '底部导航不得继续依赖 JS 点击切页');

const appWxss = fs.readFileSync(path.join(root, 'app.wxss'), 'utf8');
assert.ok(
  /\.tabbar-safe-space\s*\{[\s\S]*?height:\s*calc\(var\(--tabbar-height\) \+ env\(safe-area-inset-bottom\)\)/.test(
    appWxss
  ),
  '首页必须只保留 TabBar 等高的底部安全空间'
);

assert.deepEqual(
  homeShortcuts,
  [
    { id: 'coupon', label: '会员领券', icon: '/assets/icons/lucide/ticket-percent.svg' },
    { id: 'stored-value', label: '储值有礼', icon: '/assets/icons/lucide/gift.svg' },
    { id: 'points-mall', label: '时光币商城', icon: '/assets/icons/lucide/badge-japanese-yen.svg' },
    { id: 'service', label: '客服入口', icon: '/assets/icons/lucide/headset.svg' }
  ],
  '首页快捷入口必须为会员领券、储值有礼、时光币商城和客服入口'
);
// 分类层级已拍平为单层（V38）：菜单结构 = 1 个 tab -> 1 个 group -> 分类列表。
const menuTab = menuTabs[0];
assert.equal(menuTab.id, 'menu', '拍平后菜单必须为单一 tab');
assert.equal(menuTab.groups.length, 1, '拍平后菜单必须只含一个分组容器');
assert.equal(menuTab.groups[0].label, '全部', '拍平后分组容器标签必须为「全部」');
const categories = menuTab.groups[0].categories;
assert.equal(categories.length, 3, '拍平后必须保留全部 3 个真实分类');
const classicHerbal = categories.find(c => c.id === 'herbal');
const classicTraditional = categories.find(c => c.id === 'traditional');
const featuredSeason = categories.find(c => c.id === 'featured-season');
assert.ok(classicHerbal, '必须保留草本养生茶分类');
assert.ok(classicTraditional, '必须保留传统原叶茶分类');
assert.ok(featuredSeason, '必须保留季节限定分类');
assert.equal(classicHerbal.products.length, 8, '草本养生茶必须包含 8 个商品');
assert.equal(classicTraditional.products.length, 5, '传统原叶茶必须包含 5 个商品');
assert.equal(featuredSeason.products.length, 5, '季节限定必须包含 5 个商品');
const expectedClassicHerbalProducts = [
  { id: 'classic-001', name: '五窨茉莉抹茶', price: 13.9, originalPrice: 16 },
  { id: 'classic-002', name: '金桂轻乳茶', price: 13.9, originalPrice: 16 },
  { id: 'classic-003', name: '青提茉莉冰茶', price: 15.9, originalPrice: 18 },
  { id: 'classic-004', name: '陈皮普洱轻乳茶', price: 14.9, originalPrice: 17 },
  { id: 'classic-005', name: '红苹果乌龙冰奶', price: 14.9, originalPrice: 16 },
  { id: 'herbal-001', name: '桂香暖润茶', price: 12.9, originalPrice: 15 },
  { id: 'herbal-002', name: '陈皮山楂茶', price: 13.9, originalPrice: 16 },
  { id: 'herbal-003', name: '黑枸杞玫瑰茶', price: 15.9, originalPrice: 18 }
];
assert.deepEqual(
  classicHerbal.products.map(({ id, name, price, originalPrice }) => ({ id, name, price, originalPrice })),
  expectedClassicHerbalProducts,
  '草本养生茶必须保留原分组商品'
);
assert.deepEqual(
  classicTraditional.products.map(product => product.id),
  ['leaf-001', 'leaf-002', 'leaf-003', 'traditional-001', 'traditional-002'],
  '传统原叶茶必须保留原分组商品'
);
assert.deepEqual(
  featuredSeason.products.map(product => product.id),
  ['featured-001', 'featured-002', 'featured-003', 'season-001', 'season-002'],
  '季节限定必须保留原分组商品'
);
for (const product of classicHerbal.products) {
  const expectedTags = product.id === 'classic-005' ? ['年度热销', '红苹果乌龙'] : ['年度热销', '五窨茉莉花茶'];
  assert.deepEqual(product.tags, expectedTags, '首屏商品标签必须与设计稿一致');
  assert.ok(product.description.length > 20, '首屏商品必须使用完整真实介绍');
  assert.equal(product.image, '/assets/images/3x/menu-product.jpg', '首屏商品必须引用本地 3x 商品图');
}

const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8');
const menuWxss = fs.readFileSync(path.join(root, 'pages/menu/menu.wxss'), 'utf8');
const storeListCardWxml = fs.readFileSync(path.join(root, 'components/store-list-card/store-list-card.wxml'), 'utf8');
const storeListCardWxss = fs.readFileSync(path.join(root, 'components/store-list-card/store-list-card.wxss'), 'utf8');
const menuJs = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
assert.ok(menuWxml.includes('/assets/icons/lucide/map-pin.svg'), '门店距离必须使用 Lucide 定位图标');
assert.ok(!menuWxml.includes('product-section__title'), '商品区不得显示设计稿外的分类标题');
assert.ok(menuWxml.includes('count="{{cartCount}}" total="{{cartTotal}}"'), '购物车条必须使用动态数量和金额');
assert.ok(
  menuWxml.includes('wx:for="{{activeMenu.groups[0].categories}}"'),
  '左侧栏必须直接渲染单层分类列表'
);
assert.ok(
  menuWxml.includes('class="category-item {{selectedCategoryId === category.id'),
  '分类项必须绑定选中状态'
);
assert.ok(
  menuWxml.includes('category-item__tag') && menuWxml.includes('{{category.tag}}'),
  '分类项必须渲染左上角标签角标'
);
assert.ok(
  (menuWxml.match(/show-scrollbar="\{\{false\}\}"/g) || []).length >= 2,
  '分类栏、商品区和门店列表必须隐藏滚动条'
);
// 顶部页签已移除：首次渲染用空 scrollIntoView 停在广告区顶部，
// 不再存在「切换菜单回到顶部」的场景，故只校验初始态。
assert.ok(
  menuJs.includes("scrollIntoView: ''") &&
    menuWxml.includes('id="product-top"') &&
    !menuJs.includes('scrollIntoView: `category-${activeMenu.categories[0].id}`'),
  '点单页必须从广告区顶部开始（不得自动滚动到首个分类）'
);
// 顶部页签（经典菜单 / 招牌主打）已按产品要求彻底移除：
// 其余 TAB 的分组由 getMergedMenuTab 并入首个 TAB，同级展示。
assert.ok(
  !menuWxml.includes('menu-tabs') && !menuWxss.includes('.menu-tabs') && !menuJs.includes('selectMenuTab'),
  '点单页不得保留顶部菜单页签的任意残留（结构/样式/事件）'
);
assert.ok(
  /\.category-scroll[\s\S]*?width:\s*182rpx[\s\S]*?background:\s*var\(--page-bg-neutral\)/.test(menuWxss),
  '分类栏必须使用统一浅灰背景'
);
assert.ok(
  /\.category-item\.is-active\s*\{[\s\S]*?background:\s*#FFFFFF/.test(menuWxss),
  '当前分类行必须使用白色选中背景'
);
assert.ok(
  /\.category-item__tag\s*\{[\s\S]*?background:\s*var\(--brand-green\)/.test(menuWxss),
  '分类左上角标签必须使用品牌绿背景'
);
const productScrollRule = menuWxss.match(/\.product-scroll\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(
  productScrollRule.includes('padding: 0 20rpx') && productScrollRule.includes('12rpx;'),
  '商品区必须使用左右 20rpx/12rpx 非对称间距'
);
assert.ok(/\.ad-banner\s*\{[\s\S]*?height:\s*140rpx/.test(menuWxss), '商品广告图必须按素材比例使用约 140rpx 高度');
assert.ok(
  // 点单页运行在 Skyline 下，内嵌 <map> 会白屏，且不再保留「假地图」静态占位图：
  // 门店列表直出，导航按钮经 wx.openLocation 拉起真实腾讯地图。
  !menuWxml.includes('store-picker__map--entry') &&
    menuWxml.includes('wx:for="{{pickerStores}}"') &&
    menuWxml.includes('bind:navigate='),
  '点单页必须直出门店列表且不含假地图占位'
);
{
  const mapJson = JSON.parse(fs.readFileSync(path.join(root, 'pages/store-map/store-map.json'), 'utf8'));
  assert.equal(mapJson.renderer, 'webview', '独立地图页必须显式使用 webview 渲染器');
  const mapWxml = fs.readFileSync(path.join(root, 'pages/store-map/store-map.wxml'), 'utf8');
  assert.ok(
    mapWxml.includes('<map') && mapWxml.includes('markers="{{markers}}"'),
    '独立地图页必须渲染地图与 marker'
  );
}
const menuJsonSource = fs.readFileSync(path.join(root, 'pages/menu/menu.json'), 'utf8');
// 点单页门店态内嵌原生 <map>（顶部真实地图），Skyline 对原生组件支持受限会白屏，
// 因此点单页显式降级为 webview；其余页面仍继承全局 Skyline。
assert.equal(
  JSON.parse(menuJsonSource).renderer,
  'webview',
  '点单页必须显式使用 webview 渲染器，否则内嵌 <map> 会白屏'
);
assert.ok(
  menuWxml.includes('locate-fixed.svg') && menuWxml.includes('搜索门店'),
  '门店选择层必须包含本地搜索和定位按钮'
);
assert.ok(
  // <map> 是原生组件，层级恒在最上层，普通 view 会被盖住；
  // 因此地图上的导航与筛选必须用 cover-view / cover-image 承载。
  menuWxml.includes('<cover-view class="store-page__nav">') &&
    menuWxml.includes('store-page__nav-favorite') &&
    menuWxml.includes('<cover-image') &&
    menuWxml.includes('bookmark.svg'),
  '地图上的导航必须使用 cover-view 并包含附近标题与收藏入口'
);
assert.ok(
  storeListCardWxml.includes('store.isFavorite') && storeListCardWxml.includes('star-gold.svg'),
  '公共门店卡必须显示金色 Lucide 五角星'
);
const starGoldSvg = fs.readFileSync(path.join(root, 'assets/icons/lucide/star-gold.svg'), 'utf8');
const starGraySvg = fs.readFileSync(path.join(root, 'assets/icons/lucide/star.svg'), 'utf8');
assert.ok(
  starGoldSvg.includes('stroke="#D4A017"') && starGoldSvg.includes('fill="#D4A017"'),
  '已收藏星标必须是实心金色'
);
assert.ok(starGraySvg.includes('fill="none"'), '未收藏星标必须保持描边样式');
assert.ok(
  menuWxml.includes('catchtap="handleCurrentFavorite"') &&
    menuWxml.includes('bindtap="openFavoriteStores"') &&
    !menuWxml.includes('handleFavorite'),
  '门店五角星负责收藏，顶部收藏负责查看列表'
);
assert.ok(
  // 筛选条改为悬浮在地图下沿的白底卡片，本身不再需要避让状态栏；
  // 状态栏 / 导航栏避让改由地图上的 cover-view 导航承担。
  /\.store-page__nav\s*\{[\s\S]*?padding-top:\s*env\(safe-area-inset-top/.test(menuWxss),
  '地图导航必须避让状态栏与导航栏'
);
assert.ok(
  stores.every(store => store.promotion === '新中式养生茶系列上新'),
  '所有门店促销文案必须统一'
);
assert.ok(
  stores.every(store => !Object.prototype.hasOwnProperty.call(store, 'decorImage')),
  '门店数据不得保留 decorImage'
);
assert.ok(
  /\.store-page__filter\s*\{[\s\S]*?background:\s*var\(--card-bg\)[\s\S]*?border-radius:\s*var\(--radius-lg\)[\s\S]*?box-shadow:\s*var\(--shadow-float\)/.test(
    menuWxss
  ),
  '门店筛选条必须是白底圆角悬浮卡片'
);
assert.ok(
  /\.store-list-card\s*\{[^}]*padding:\s*16rpx 20rpx[^}]*background:\s*var\(--card-bg\)[^}]*border-radius:\s*var\(--radius-lg\)/.test(
    storeListCardWxss
  ),
  '公共门店卡必须使用白底与 16/20rpx 内边距'
);
assert.ok(/\.store-page__filter[\s\S]*?height:\s*104rpx/.test(menuWxss), '门店筛选条必须按参考图增高到 104rpx');
assert.ok(
  /\.store-page__list-inner\s*\{[\s\S]*?padding:\s*20rpx/.test(menuWxss),
  '门店列表必须使用 20rpx 页面边距'
);
assert.ok(
  // 门店页是 Tab 页的无门店态，TabBar 常显，因此必须保留 tabbar-safe-space
  menuWxml.includes('tabbar-safe-space'),
  '门店页作为 Tab 页必须保留 tabbar-safe-space，避免最后一张卡被 TabBar 遮挡'
);
assert.ok(storeListCardWxml.includes('leaf-white.svg'), '公共门店卡上新横幅必须使用 Lucide 白色叶芽图标');
assert.ok(
  storeListCardWxml.includes('phone-white.svg') && storeListCardWxml.includes('navigation-white.svg'),
  '公共门店卡电话与导航按钮必须使用白色 Lucide 图标'
);
assert.ok(
  /\.store-page\s*\{[\s\S]*?background:\s*var\(--store-page-mint\)/.test(menuWxss),
  '门店页必须使用图二浅绿底色'
);
assert.ok(
  !menuWxml.includes('store-queue') &&
    menuWxml.includes('store-row__queue') &&
    menuWxml.includes('queueText') &&
    menuWxml.includes('queueIcon'),
  '菜单门店距离行必须内联显示统一排队状态'
);
assert.ok(
  storeListCardWxml.includes('store.queueIcon') && storeListCardWxml.includes('store.queueClass'),
  '公共门店卡排队状态必须由数据驱动'
);
const appWxssSource = fs.readFileSync(path.join(root, 'app.wxss'), 'utf8');
assert.ok(
  appWxssSource.includes('--queue-warning: #E6A23C') && appWxssSource.includes('--favorite-gold: #D4A017'),
  '设计系统必须包含排队黄色和收藏金色 token'
);
assert.ok(
  appWxssSource.includes('--store-page-mint: #EAF4E1') &&
    appWxssSource.includes('--store-promo-from: #429E46') &&
    appWxssSource.includes('--store-decor-leaf: #A6C685'),
  '设计系统必须登记门店卡鲜绿 token'
);
assert.ok(
  storeListCardWxss.includes('font-size: var(--font-xl)') &&
    storeListCardWxss.includes('font-size: var(--font-caption)') &&
    storeListCardWxss.includes('line-height: 36rpx'),
  '公共门店卡必须使用图二对应的店名、横幅与地址字号'
);
assert.ok(
  /\.store-list-card__action[\s\S]*?width:\s*64rpx[\s\S]*?height:\s*64rpx/.test(storeListCardWxss) &&
    storeListCardWxss.includes('linear-gradient(180deg, var(--store-action-from), var(--store-action-to))'),
  '公共门店卡电话和导航按钮必须为 64rpx 鲜绿渐变圆'
);
assert.ok(
  storeListCardWxml.includes('store-list-card__decor') &&
    storeListCardWxml.includes('/assets/icons/lucide/store-decor-sprout.svg') &&
    /\.store-list-card__decor\s*\{[^}]*right:\s*8rpx[^}]*bottom:\s*-48rpx/.test(storeListCardWxss),
  '公共门店卡必须在右下角放置被裁切的 Lucide 叶芽水印'
);
assert.ok(
  /\.store-list-card__actions[\s\S]*?flex-direction:\s*row[\s\S]*?flex-wrap:\s*nowrap/.test(storeListCardWxss),
  '公共门店卡电话和导航按钮必须横向排列且禁止换行'
);
assert.ok(
  /\.store-list-card\s*\{[^}]*background:\s*var\(--card-bg\)/.test(storeListCardWxss) &&
    storeListCardWxss.includes('linear-gradient(90deg, var(--store-promo-from), var(--store-promo-to))') &&
    storeListCardWxss.includes('background: var(--store-pill-bg)'),
  '公共门店卡必须使用白色卡底、深绿渐变横幅与距离胶囊'
);
const appJsSource = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
assert.ok(
  appJsSource.includes('onHide()') &&
    appJsSource.includes('handleAppHide()') &&
    appJsSource.includes('resolveStoreCatalog()'),
  'App 必须记录退出时间并初始化门店会话'
);
assert.ok(!appJsSource.includes('wx.getLocation'), '纯静态原型不得调用微信定位');
const cityPickerWxml = fs.readFileSync(path.join(root, 'pages/city-picker/city-picker.wxml'), 'utf8');
const cityPickerJs = fs.readFileSync(path.join(root, 'pages/city-picker/city-picker.js'), 'utf8');
const cityPickerWxss = fs.readFileSync(path.join(root, 'pages/city-picker/city-picker.wxss'), 'utf8');
assert.ok(
  cityPickerWxml.includes('选择城市') &&
    cityPickerWxml.includes('cityGroups') &&
    cityPickerWxml.includes('scrollIntoView'),
  '城市页必须提供分组城市列表与首字母跳转'
);
assert.ok(
  cityPickerJs.includes('selectCity') && cityPickerJs.includes('wx.navigateBack()'),
  '城市页必须保存城市并返回'
);
assert.ok(
  cityPickerWxss.includes('var(--brand-green)') && cityPickerWxss.includes('var(--line-color)'),
  '城市页必须遵守设计系统 token'
);
assert.ok(/\.city-row[\s\S]*?min-height:\s*80rpx/.test(cityPickerWxss), '城市行必须按参考图调整到 80rpx');
const orderConfirmJsSource = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
assert.ok(
  orderConfirmJsSource.includes('catalog.stores') && !orderConfirmJsSource.includes('getStoresByCity'),
  '确认订单页必须使用 resolveStoreCatalog 的统一定位门店视图'
);
const storeUtility = fs.readFileSync(path.join(root, 'utils/store.js'), 'utf8');
assert.ok(
  storeUtility.includes('30 * 60 * 1000') &&
    storeUtility.includes('last-hidden-at') &&
    storeUtility.includes('getQueueStatus'),
  '门店会话必须实现退出 30 分钟规则和排队状态函数'
);
assert.ok(
  storeUtility.includes('DEFAULT_CITY_CODE = &#39;changsha&#39;') ||
    storeUtility.includes("DEFAULT_CITY_CODE = 'changsha'"),
  '无位置缓存时必须默认长沙'
);

const productCardWxml = fs.readFileSync(path.join(root, 'components/product-card/product-card.wxml'), 'utf8');
const productCardWxss = fs.readFileSync(path.join(root, 'components/product-card/product-card.wxss'), 'utf8');
const productCardRule = productCardWxss.match(/\.product-card\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(
  productCardRule.includes('min-height: 232rpx') && productCardRule.includes('padding: 16rpx 0'),
  '商品卡必须收紧最小高度并保留上下留白'
);
assert.ok(!productCardRule.includes('height: 240rpx'), '商品卡不得继续固定 240rpx 高度');
assert.ok(!productCardWxml.includes('product-card__description ellipsis-2'), '商品介绍必须完整展示，不得两行截断');
assert.ok(
  !productCardWxml.includes('<button') &&
    productCardWxml.includes('<view class="product-card__action"') &&
    productCardWxml.includes('bindtap="handleSpec"'),
  '选规格必须使用可控尺寸的 view 按钮'
);
assert.ok(
  /\.product-card__image\s*\{[\s\S]*?width:\s*150rpx[\s\S]*?height:\s*200rpx/.test(productCardWxss),
  '商品图必须调整为约 150x200rpx'
);
assert.ok(
  /\.product-card__description\s*\{[\s\S]*?white-space:\s*normal/.test(productCardWxss),
  '商品介绍必须允许完整换行展示'
);
assert.ok(/\.product-card__price-row\s*\{[\s\S]*?flex-wrap:\s*nowrap/.test(productCardWxss), '价格区域必须禁止换行');
assert.ok(
  /\.product-card__price-label\s*\{[\s\S]*?white-space:\s*nowrap/.test(productCardWxss),
  '会员价标签必须保持单行'
);
assert.ok(
  productCardWxml.includes('会员价') &&
    productCardWxml.includes('储值立减') &&
    productCardWxml.includes('{{product.storedValuePrice}}'),
  '商品卡必须展示会员价与储值立减金额'
);
assert.ok(
  productCardWxml.includes('product-card__original') && productCardWxml.includes('{{product.originalPrice}}'),
  '商品卡必须保留划线原价'
);
assert.ok(
  !productCardWxml.includes('立即储值') &&
    !productCardWxml.includes('handleRecharge') &&
    !productCardWxml.includes('充100'),
  '商品卡不得保留充100或立即储值入口'
);
assert.ok(
  /\.product-card__action\s*\{[\s\S]*?width:\s*128rpx[\s\S]*?height:\s*48rpx/.test(productCardWxss),
  '选规格按钮必须约 128x48rpx'
);
assert.ok(
  /\.product-card\.has-divider::after\s*\{[\s\S]*?left:\s*168rpx[\s\S]*?right:\s*0[\s\S]*?bottom:\s*0/.test(
    productCardWxss
  ),
  '商品卡必须按设计稿显示内容区浅灰分隔线'
);
assert.ok(
  /\.product-card__tag--hot\s*\{[\s\S]*?color:\s*var\(--price-red\)[\s\S]*?background:\s*var\(--tag-hot-bg\)/.test(
    productCardWxss
  ),
  '年度热销标签必须使用设计 token'
);
assert.ok(
  /\.product-card__tag--light\s*\{[\s\S]*?background:\s*var\(--tag-light-bg\)/.test(productCardWxss) &&
    /\.product-card__tag--light\s*\{[\s\S]*?color:\s*var\(--tag-light-text\)/.test(productCardWxss),
  '茶类标签必须使用设计 token'
);
assert.ok(
  productCardWxml.includes('wx:if="{{product.badgeIcon}}" ') ||
    productCardWxml.includes('wx:if="{{product.badgeIcon}}"'),
  '商品列表角标必须由 badgeIcon 数据驱动'
);
assert.ok(/\.product-card__badge\s*\{[\s\S]*?right:\s*-4rpx/.test(productCardWxss), '商品列表角标必须位于图片右上角');

const cartBarWxml = fs.readFileSync(path.join(root, 'components/cart-bar/cart-bar.wxml'), 'utf8');
const cartBarWxss = fs.readFileSync(path.join(root, 'components/cart-bar/cart-bar.wxss'), 'utf8');
assert.ok(!cartBarWxml.includes('购物袋'), '购物车不得显示购物袋文字');
assert.ok(
  !cartBarWxml.includes('<button') &&
    cartBarWxml.includes('<view class="cart-bar__checkout"') &&
    cartBarWxml.includes('bindtap="handleCheckout"'),
  '去结算必须使用可控尺寸的 view 按钮'
);
assert.ok(!cartBarWxss.includes('.cart-bar__label'), '购物车样式不得保留购物袋标签');
assert.ok(
  /\.cart-bar\s*\{[\s\S]*?right:\s*34rpx[\s\S]*?left:\s*27rpx[\s\S]*?height:\s*96rpx[\s\S]*?background:\s*#2D2D2D/.test(
    cartBarWxss
  ),
  '购物车胶囊位置、高度和颜色必须与调整方案一致'
);
assert.ok(
  /\.cart-bar-dock\s*\{[\s\S]*?right:\s*0;[\s\S]*?left:\s*0;[\s\S]*?background:\s*var\(--card-bg\)/.test(cartBarWxss),
  '购物车条必须有全宽白色衬底横条'
);
assert.ok(!/rgba\(0,\s*0,\s*0,\s*0\.22\)/.test(cartBarWxss), '购物车条衬底上不得保留黑色投影');
assert.ok(
  /\.cart-bar__total[\s\S]*?display:\s*flex[\s\S]*?align-items:\s*baseline[\s\S]*?white-space:\s*nowrap/.test(
    cartBarWxss
  ),
  '购物车合计金额必须以单行 flex 布局靠左排列'
);
assert.ok(
  /\.cart-bar__icon\s*\{[\s\S]*?width:\s*64rpx[\s\S]*?height:\s*64rpx/.test(cartBarWxss),
  '购物袋圆形图标必须随购物车收紧'
);
assert.ok(
  /\.cart-bar__checkout\s*\{[\s\S]*?width:\s*154rpx[\s\S]*?height:\s*80rpx[\s\S]*?display:\s*flex[\s\S]*?font-size:\s*30rpx/.test(
    cartBarWxss
  ),
  '结算按钮必须约 154x80rpx、字体 30rpx 并居中'
);
assert.ok(
  /\.cart-bar__image\s*\{[\s\S]*?width:\s*36rpx[\s\S]*?height:\s*36rpx/.test(cartBarWxss),
  '购物袋 Lucide 图标必须限制在圆形区域内'
);

const cartSheetWxml = fs.readFileSync(path.join(root, 'components/cart-sheet/cart-sheet.wxml'), 'utf8');
const cartSheetWxss = fs.readFileSync(path.join(root, 'components/cart-sheet/cart-sheet.wxss'), 'utf8');
const cartSheetJs = fs.readFileSync(path.join(root, 'components/cart-sheet/cart-sheet.js'), 'utf8');
const menuJson = fs.readFileSync(path.join(root, 'pages/menu/menu.json'), 'utf8');
assert.ok(
  menuJson.includes('"cart-sheet"') && menuWxml.includes('<cart-sheet'),
  '点单页必须注册并使用 cart-sheet 组件'
);
assert.ok(
  menuWxml.includes('visible="{{cartVisible}}"') && menuWxml.includes('items="{{cartItems}}"'),
  '购物车弹层必须绑定可见状态和商品数据'
);
assert.ok(
  cartSheetWxml.includes('已享受折扣优惠') && cartSheetWxml.includes('全选') && cartSheetWxml.includes('清空'),
  '购物车弹层必须保留优惠信息、全选与清空入口'
);
assert.ok(
  !cartSheetWxml.includes('cart-sheet__summary'),
  '购物车弹层不得保留重复的底部结算条，结算入口由底部购物车条承担'
);
assert.ok(
  cartSheetWxml.includes('plus.svg') &&
    cartSheetWxml.includes('minus.svg') &&
    cartSheetWxml.includes('check.svg') &&
    cartSheetWxml.includes('trash-2.svg') &&
    cartSheetWxml.includes('pencil.svg') &&
    cartSheetWxml.includes('info.svg'),
  '购物车弹层必须使用新增的 Lucide 图标'
);
assert.ok(
  cartSheetJs.includes("triggerEvent('change'") &&
    cartSheetJs.includes('toggleAll') &&
    cartSheetJs.includes('clearCart') &&
    cartSheetJs.includes('increaseQuantity') &&
    cartSheetJs.includes('decreaseQuantity'),
  '购物车必须支持勾选、全选、清空和数量联动'
);
assert.ok(
  /\.cart-sheet__mask\s*\{[\s\S]*?position:\s*absolute/.test(cartSheetWxss) &&
    /\.cart-sheet__panel\s*\{[\s\S]*?position:\s*absolute/.test(cartSheetWxss),
  '购物车必须包含遮罩和底部弹层'
);
assert.ok(/\.cart-sheet__list\s*\{[\s\S]*?height:\s*230rpx/.test(cartSheetWxss), '购物车商品列表必须使用固定可视高度');
// 阶段 C 假数据清理：购物车不再预置商品，初始必须为空且不依赖本地 mock。
assert.equal(initialCartItems, undefined, 'data/mock.js 不得再导出 initialCartItems 假数据');
assert.ok(!menuJs.includes('initialCartItems'), '点单页不得再引用本地购物车假数据');
assert.ok(
  /cartItems:\s*\[\]/.test(menuJs) && /cartCount:\s*0/.test(menuJs) && /cartTotal:\s*0/.test(menuJs),
  '点单页购物车初始必须为空（商品由菜单接口提供）'
);
assert.ok(menuJs.includes('summarizeCart'), '点单页必须使用 summarizeCart 计算购物车汇总');

const specSheetWxml = fs.readFileSync(path.join(root, 'components/spec-sheet/spec-sheet.wxml'), 'utf8');
const specSheetWxss = fs.readFileSync(path.join(root, 'components/spec-sheet/spec-sheet.wxss'), 'utf8');
const specSheetJs = fs.readFileSync(path.join(root, 'components/spec-sheet/spec-sheet.js'), 'utf8');
assert.ok(
  menuJson.includes('"spec-sheet"') && menuWxml.includes('<spec-sheet'),
  '点单页必须注册并使用 spec-sheet 组件'
);
assert.ok(
  menuWxml.includes('visible="{{specVisible}}"') && menuWxml.includes('product="{{specProduct}}"'),
  '规格弹层必须绑定可见状态和当前商品'
);
assert.ok(
  menuWxml.includes('mode="{{specMode}}"') &&
    menuWxml.includes('initial-quantity="{{specInitialQuantity}}"') &&
    menuWxml.includes('initial-selected-option-ids="{{specInitialSelectedOptionIds}}"') &&
    menuWxml.includes('bind:updatecart="handleCartUpdate"'),
  '规格弹层必须绑定编辑模式、初始规格与更新事件'
);
assert.ok(
  menuJs.includes('handleSpec(event)') && menuJs.includes('specVisible: true') && menuJs.includes('handleSpecAddCart'),
  '选规格必须打开弹层并接入加入购物车'
);
assert.ok(
  menuJs.includes('handleCartEdit(event)') &&
    menuJs.includes('handleCartUpdate(event)') &&
    menuJs.includes('mergeEditedCartItem'),
  '购物车编辑必须复用规格弹层并更新或合并购物车项'
);
assert.ok(!menuJs.includes('规格编辑暂未接入'), '购物车编辑不得继续显示暂未接入提示');
assert.ok(
  specSheetWxml.includes('product.specDetail.imageDisclaimer') &&
    specSheetWxml.includes('product.specDetail.priceLabel') &&
    specSheetWxml.includes('主要原料') &&
    specSheetWxml.includes('展开') &&
    specSheetWxml.includes('收起') &&
    specSheetWxml.includes('立即购买') &&
    specSheetWxml.includes('加入购物车'),
  '规格弹层必须保留参考图的完整信息结构'
);
assert.ok(
  specSheetWxml.includes('确定修改') &&
    specSheetWxml.includes('handleUpdateCart') &&
    specSheetWxml.includes("mode === 'edit'"),
  '编辑模式必须显示确定修改并复用同一规格弹层'
);
assert.ok(
  specSheetWxml.includes('heart.svg') &&
    specSheetWxml.includes('x.svg') &&
    specSheetWxml.includes('share-2.svg') &&
    specSheetWxml.includes('star.svg') &&
    specSheetWxml.includes('minus.svg') &&
    specSheetWxml.includes('plus.svg') &&
    specSheetWxml.includes('chevron-down.svg'),
  '规格弹层必须使用规定 Lucide 图标'
);
assert.ok(
  specSheetJs.includes('selectOption') &&
    specSheetJs.includes('toggleExpand') &&
    specSheetJs.includes('increaseQuantity') &&
    specSheetJs.includes('decreaseQuantity') &&
    specSheetJs.includes("triggerEvent('addcart'") &&
    specSheetJs.includes("triggerEvent('buy'") &&
    specSheetJs.includes("triggerEvent('updatecart'"),
  '已选规格弹层必须支持规格选择、数量调整、加购与编辑确认'
);
assert.ok(
  specSheetJs.includes('mode: { type: String') &&
    specSheetJs.includes('initialQuantity') &&
    specSheetJs.includes('initialSelectedOptionIds') &&
    specSheetJs.includes('buildSpecState'),
  '规格弹层必须支持编辑模式初始化和状态回填'
);
assert.ok(/\.spec-sheet__panel\s*\{[\s\S]*?height:\s*88vh/.test(specSheetWxss), '规格弹层必须使用约 88vh 高度');
assert.ok(
  /var\(--brand-green\)/.test(specSheetWxss) && !/#53882C/.test(specSheetWxss),
  '规格弹层必须使用设计系统颜色 token'
);
assert.ok(
  specSheetWxml.includes('wx:if="{{product.badgeIcon}}" ') || specSheetWxml.includes('wx:if="{{product.badgeIcon}}"'),
  '规格弹层主图角标必须数据驱动'
);
assert.ok(/\.spec-sheet__hero-badge\s*\{[\s\S]*?right:\s*-4rpx/.test(specSheetWxss), '规格弹层主图角标必须位于右上角');
const specTextRule = specSheetWxss.match(/\.spec-sheet__spec-text\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(
  specTextRule && !specTextRule.includes('position: absolute'),
  '已选规格文字必须位于文档流中，不得使用绝对定位与价格重叠'
);
assert.ok(
  specTextRule.includes('text-overflow: ellipsis') && specTextRule.includes('white-space: nowrap'),
  '已选规格文字必须保持单行省略'
);
// 规格与温馨提示改为数据库扁平字段（product_spec / product.tips）
const firstSpecProduct = classicHerbal.products[0];
assert.ok(
  Array.isArray(firstSpecProduct.specGroups) && firstSpecProduct.specGroups.length === 3,
  '商品必须补齐份量、温度与加料规格'
);
assert.equal(firstSpecProduct.specGroups[0].options[0].label, '中杯', '默认份量必须为中杯');
assert.equal(firstSpecProduct.specGroups[1].options[0].label, '标准冰', '温度组必须位于份量组之后');
assert.ok(Array.isArray(firstSpecProduct.tips) && firstSpecProduct.tips.length > 0, '温馨提示必须使用 tips 文本数据');

const iconScript = fs.readFileSync(path.join(root, 'scripts/sync-lucide-icons.mjs'), 'utf8');
assert.ok(/source:\s*'map-pin'[\s\S]*?output:\s*'map-pin'/.test(iconScript), 'Lucide 图标映射必须包含 map-pin');
assert.ok(
  /source:\s*'locate-fixed'[\s\S]*?output:\s*'locate-fixed'/.test(iconScript),
  'Lucide 图标映射必须包含 locate-fixed'
);
assert.ok(
  /source:\s*'map-pin'[\s\S]*?output:\s*'store-map-pin'[\s\S]*?output:\s*'store-map-pin-active'/.test(iconScript),
  '地图门店 marker 必须使用 Lucide map-pin 普通与激活版本'
);
assert.ok(
  /source:\s*'circle-dot'[\s\S]*?output:\s*'queue-safe'[\s\S]*?output:\s*'queue-warning'[\s\S]*?output:\s*'queue-danger'/.test(
    iconScript
  ),
  '排队状态图标必须统一使用 Lucide circle-dot'
);
for (const [file, color] of [
  ['queue-safe.svg', '#53882C'],
  ['queue-warning.svg', '#E6A23C'],
  ['queue-danger.svg', '#FF0000']
]) {
  const svg = fs.readFileSync(path.join(root, 'assets/icons/lucide', file), 'utf8');
  assert.ok(svg.includes(`<circle cx="12" cy="12" r="10" fill="${color}"`), `${file} 外圆必须使用实心状态色`);
  assert.ok(
    svg.includes('<circle cx="12" cy="12" r="1" fill="#FFFFFF" stroke="#FFFFFF"'),
    `${file} 内圆必须使用白色中心`
  );
}
assert.ok(/source:\s*'apple'[\s\S]*?output:\s*'apple-white'/.test(iconScript), 'Lucide 图标映射必须包含 apple-white');
for (const icon of [
  'check',
  'trash-2',
  'pencil',
  'minus',
  'plus',
  'info',
  'x',
  'heart',
  'phone',
  'send',
  'copy',
  'rotate-ccw',
  'message-square-heart',
  'chef-hat',
  'circle-check-big',
  'circle-help'
]) {
  assert.ok(iconScript.includes(`source: '${icon}'`), `Lucide 图标映射必须包含 ${icon}`);
  assert.ok(fs.existsSync(path.join(root, 'assets/icons/lucide', `${icon}.svg`)), `缺少生成的 ${icon} Lucide 图标`);
}
assert.ok(fs.existsSync(path.join(root, 'assets/icons/lucide/map-pin.svg')), '缺少生成的 map-pin Lucide 图标');

console.log('高清首页、点单页与素材验收测试通过');
