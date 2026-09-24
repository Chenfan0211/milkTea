import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appJsonPath = path.join(root, 'app.json');
const projectConfigPath = path.join(root, 'project.config.json');
const expectedPages = [
  'pages/launch/launch',
  'pages/home/home',
  'pages/menu/menu',
  'pages/member/member',
  'pages/orders/orders',
  'pages/profile/profile',
  'pages/auth-login/auth-login',
  'pages/legal/legal',
  'pages/service/service',
  'pages/profile-data/profile-data',
  'pages/stored-value/stored-value',
  'pages/activity-rules/activity-rules',
  'pages/favorite-stores/favorite-stores',
  'pages/order-detail/order-detail',
  'pages/coupon-list/coupon-list',
  'pages/points-mall/points-mall',
  'pages/points-signin/points-signin',
  'pages/points-signin-rules/points-signin-rules',
  'pages/points-exchange/points-exchange',
  'pages/points-detail/points-detail',
  'pages/exchange-records/exchange-records',
  'pages/gift-card/gift-card',
  'pages/gift-card-orders/gift-card-orders',
  'pages/gift-card-purchase/gift-card-purchase',
  'pages/order-confirm/order-confirm',
  'pages/coupon-stores/coupon-stores',
  'pages/coupon-products/coupon-products',
  'pages/city-picker/city-picker',
  'pages/store-map/store-map',
  'pages/member-rights/member-rights',
  'pages/member-level-rules/member-level-rules',
  'pages/role-center/role-center',
  'pages/role-workbench/role-workbench',
  'pages/role-apply/role-apply',
  'pages/resource-orders/resource-orders',
  'pages/role-verify/role-verify',
  'pages/role-products/role-products',
  'pages/role-product-detail/role-product-detail',
  'pages/role-income/role-income',
  'pages/role-invest/role-invest',
  'pages/role-invest-apply/role-invest-apply',
  'pages/role-invest-records/role-invest-records',
  'pages/role-invest-detail/role-invest-detail',
  'pages/role-income-records/role-income-records',
  'pages/role-income-detail/role-income-detail',
  'pages/role-income-rules/role-income-rules',
  'pages/role-withdraw/role-withdraw',
  'pages/role-withdraw-records/role-withdraw-records',
  'pages/role-withdraw-rules/role-withdraw-rules',
  'pages/role-withdraw-detail/role-withdraw-detail',
  'pages/share-referral/share-referral'
];
const expectedTabBarPages = [
  'pages/home/home',
  'pages/menu/menu',
  'pages/member/member',
  'pages/orders/orders',
  'pages/profile/profile'
];
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
  ['assets/images/3x/points-product-pet.jpg', { width: 750, height: 646, maxBytes: 80 * 1024 }],
  ['assets/images/3x/points-product-matcha.jpg', { width: 750, height: 646, maxBytes: 80 * 1024 }],
  ['assets/images/3x/points-product-single.jpg', { width: 510, height: 456, maxBytes: 15 * 1024 }],
  ['assets/images/3x/points-product-half.jpg', { width: 510, height: 456, maxBytes: 15 * 1024 }],
  ['assets/images/3x/points-empty.jpg', { width: 650, height: 520, maxBytes: 15 * 1024 }],
  ['assets/images/3x/points-signin-calendar.jpg', { width: 600, height: 400, maxBytes: 80 * 1024 }],
  ['assets/images/3x/stored-value-banner.jpg', { width: 1053, height: 468, maxBytes: 120 * 1024 }]
]);
const requiredPackIgnore = ['design/reference', 'assets/temp', 'scripts', 'docs'];
const errors = [];
let appJson;
let packageJson;
let projectConfig;

try {
  appJson = JSON.parse(fs.readFileSync(appJsonPath, 'utf8'));
  packageJson = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
  projectConfig = JSON.parse(fs.readFileSync(projectConfigPath, 'utf8'));
} catch (error) {
  console.error(`无法读取项目配置: ${error.message}`);
  process.exit(1);
}

for (const page of expectedPages) {
  for (const extension of ['js', 'json', 'wxml', 'wxss']) {
    const file = path.join(root, `${page}.${extension}`);
    if (!fs.existsSync(file)) errors.push(`缺少页面文件: ${page}.${extension}`);
  }
}

if (JSON.stringify(appJson.pages) !== JSON.stringify(expectedPages)) {
  errors.push(`app.json pages 必须为: ${expectedPages.join(', ')}`);
}

const tabBarPages = (appJson.tabBar?.list || []).map(item => item.pagePath);
if (!appJson.tabBar?.custom) errors.push('app.json 必须启用 custom tabBar');
if (JSON.stringify(tabBarPages) !== JSON.stringify(expectedTabBarPages)) {
  errors.push(`tabBar 页面必须为: ${expectedTabBarPages.join(', ')}`);
}

const customTabBarFiles = ['index.js', 'index.json', 'index.wxml', 'index.wxss'];
for (const file of customTabBarFiles) {
  const target = path.join(root, 'custom-tab-bar', file);
  if (!fs.existsSync(target)) errors.push(`缺少自定义 Tab Bar 文件: custom-tab-bar/${file}`);
}

for (const file of [
  'data/mock.js',
  'components/product-card/product-card.js',
  'components/cart-bar/cart-bar.js',
  'components/empty-state/empty-state.js',
  'scripts/sync-lucide-icons.mjs'
]) {
  if (!fs.existsSync(path.join(root, file))) errors.push(`缺少公共文件: ${file}`);
}

if (packageJson.devDependencies?.['lucide-static'] !== '1.46.0') {
  errors.push('package.json 必须锁定 lucide-static@1.46.0');
}

const lucideDir = path.join(root, 'assets', 'icons', 'lucide');
if (!fs.existsSync(lucideDir)) {
  errors.push('缺少 Lucide 图标目录: assets/icons/lucide');
} else {
  const lucideFiles = fs.readdirSync(lucideDir).filter(file => file.endsWith('.svg'));
  if (!lucideFiles.length) errors.push('assets/icons/lucide 中没有 SVG 图标');
  for (const file of lucideFiles) {
    const content = fs.readFileSync(path.join(lucideDir, file), 'utf8');
    if (!content.includes('@license lucide-static')) errors.push(`非 Lucide 图标: assets/icons/lucide/${file}`);
    if (content.includes('currentColor')) errors.push(`Lucide 图标未固化颜色: assets/icons/lucide/${file}`);
  }
}

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

const sourceFiles = walk(root).filter(
  file => !file.includes(`${path.sep}assets${path.sep}`) && !file.includes(`${path.sep}node_modules${path.sep}`)
);
for (const file of sourceFiles.filter(file => file.endsWith('.js'))) {
  const result = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
  if (result.status !== 0) errors.push(`JavaScript 语法错误: ${path.relative(root, file)}\n${result.stderr.trim()}`);
}

for (const file of sourceFiles.filter(file => /\.(js|json|wxml|wxss)$/.test(file))) {
  const content = fs.readFileSync(file, 'utf8');
  for (const match of content.matchAll(/(?:src=|(?:avatar|icon|activeIcon):\s*['`"])(\/assets\/[^'`"}\s]+)/g)) {
    const asset = path.join(root, match[1].replace(/^\//, ''));
    if (!fs.existsSync(asset)) errors.push(`资源不存在: ${match[1]} (${path.relative(root, file)})`);
    if (match[1].startsWith('/assets/icons/') && !match[1].startsWith('/assets/icons/lucide/')) {
      errors.push(`图标必须位于 assets/icons/lucide: ${match[1]} (${path.relative(root, file)})`);
    }
  }
  const runtimeFile = /[\\/](pages|components|custom-tab-bar|data)[\\/]/.test(file);
  if (runtimeFile && content.includes('/assets/temp/'))
    errors.push(`运行代码禁止引用 assets/temp: ${path.relative(root, file)}`);
  if (runtimeFile && /https?:\/\//.test(content)) errors.push(`存在远程资源引用: ${path.relative(root, file)}`);
}

const packIgnore = projectConfig.packOptions?.ignore || [];
for (const ignorePath of requiredPackIgnore) {
  const found = packIgnore.some(item => item.value === ignorePath || item.value === `${ignorePath}/`);
  if (!found) errors.push(`project.config.json packOptions.ignore 必须排除: ${ignorePath}`);
}

for (const [imagePath, expected] of expectedRuntimeImages) {
  const absolutePath = path.join(root, imagePath);
  if (!fs.existsSync(absolutePath)) {
    errors.push(`缺少 3x 运行图: ${imagePath}`);
    continue;
  }
  try {
    const size = getJpegSize(absolutePath);
    if (size.width !== expected.width || size.height !== expected.height) {
      errors.push(`${imagePath} 尺寸应为 ${expected.width}x${expected.height}，实际为 ${size.width}x${size.height}`);
    }
  } catch (error) {
    errors.push(`${imagePath} 无法读取尺寸: ${error.message}`);
  }
  const bytes = fs.statSync(absolutePath).size;
  if (bytes > expected.maxBytes) errors.push(`${imagePath} 文件过大: ${bytes} bytes`);
}

const runtimeImageBytes = [...expectedRuntimeImages.keys()].reduce(
  (total, imagePath) => total + fs.statSync(path.join(root, imagePath)).size,
  0
);
if (runtimeImageBytes > 1.8 * 1024 * 1024) errors.push(`3x 运行图总量超过 1.8MB: ${runtimeImageBytes} bytes`);

const homeWxml = fs.readFileSync(path.join(root, 'pages/home/home.wxml'), 'utf8');
if (
  !homeWxml.includes('join-card') ||
  !homeWxml.includes('/assets/images/3x/join-banner.jpg') ||
  homeWxml.includes('join-texture') ||
  homeWxml.includes('join-card__shade') ||
  homeWxml.includes('heroHeight') ||
  homeWxml.includes('capsule-repair')
) {
  errors.push('首页加盟区必须使用完整高清横幅图片，不得继续使用纹理重绘、遮罩或动态主视觉高度');
}
const homeWxss = fs.readFileSync(path.join(root, 'pages/home/home.wxss'), 'utf8');
if (
  !/\.hero-wrap[\s\S]*height:\s*1040rpx/.test(homeWxss) ||
  !/\.home-hero[\s\S]*width:\s*750rpx[\s\S]*height:\s*1040rpx/.test(homeWxss) ||
  !/margin:\s*-13rpx\s+20rpx\s+0/.test(homeWxss) ||
  !/\.home-card[\s\S]*border-radius:\s*24rpx/.test(homeWxss) ||
  !/\.join-card[\s\S]*height:\s*200rpx[\s\S]*margin:\s*6rpx\s+20rpx\s+0/.test(homeWxss)
) {
  errors.push('首页主视觉必须完整展示 750x1040rpx，并保留上移后的卡片与加盟横幅布局');
}

const profileWxml = fs.readFileSync(path.join(root, 'pages/profile/profile.wxml'), 'utf8');
const profileWxss = fs.readFileSync(path.join(root, 'pages/profile/profile.wxss'), 'utf8');
if (
  !profileWxml.startsWith('<scroll-view class="profile-scroll" scroll-y') ||
  !/\.profile-scroll[\s\S]*height:\s*100vh/.test(profileWxss) ||
  !profileWxml.includes('class="profile-hero"') ||
  !profileWxml.includes('mode="aspectFill"') ||
  !/\.profile-hero[\s\S]*width:\s*750rpx[\s\S]*height:\s*437rpx/.test(profileWxss) ||
  !/\.profile-content[\s\S]*margin-top:\s*-127rpx[\s\S]*background:\s*transparent/.test(profileWxss)
) {
  errors.push('我的页顶部图与用户卡片叠层位置必须保持调整后的尺寸');
}
const profilePageRule = profileWxss.match(/\.profile-page\s*\{([\s\S]*?)\}/)?.[1] || '';
if (/overflow(?:-y)?:\s*hidden/.test(profilePageRule)) {
  errors.push('我的页不得锁住纵向页面滚动');
}
const homeScrollTag = (homeWxml.match(/<scroll-view[^>]*>/) || [''])[0];
const profileScrollTag = (profileWxml.match(/<scroll-view[^>]*>/) || [''])[0];
const scrollSignature = tag =>
  tag
    .replace(/\s+class="[^"]*"/, '')
    .replace(/\s+/g, ' ')
    .trim();
if (!homeScrollTag || !profileScrollTag || scrollSignature(homeScrollTag) !== scrollSignature(profileScrollTag)) {
  errors.push('首页与我的页的滚动容器属性必须完全一致');
}

const ordersWxml = fs.readFileSync(path.join(root, 'pages/orders/orders.wxml'), 'utf8');
const ordersWxss = fs.readFileSync(path.join(root, 'pages/orders/orders.wxss'), 'utf8');
if (ordersWxml.includes('placeholder-page')) errors.push('订单页不得继续使用占位页结构');
if (!ordersWxml.includes('<navigation-bar') || !ordersWxml.includes('title="我的订单"'))
  errors.push('订单页必须使用自定义导航栏并显示我的订单标题');
if (!ordersWxml.includes('invoice-entry') || !ordersWxml.includes('/assets/icons/lucide/receipt.svg'))
  errors.push('订单页必须使用 Lucide receipt 图标作为开发票入口');
if (
  !ordersWxml.includes('orders-time-tabs') ||
  !ordersWxml.includes('orders-categories') ||
  !ordersWxml.includes('scroll-x')
)
  errors.push('订单页必须包含今日/历史和横滑分类筛选');
if (
  !ordersWxml.includes('order-card--{{item.category}}') ||
  !ordersWxml.includes("item.category === 'store'") ||
  !ordersWxml.includes('store-order') ||
  !ordersWxml.includes('cover-order')
)
  errors.push('订单页必须分别支持门店订单与储值/礼品卡封面订单卡片');
if (!ordersWxml.includes('catchtap="cancelOrder"') || !ordersWxml.includes('catchtap="handlePay"'))
  errors.push('待支付订单必须提供取消和立即支付操作');
if (
  !ordersWxml.includes('order-card') ||
  !ordersWxml.includes('store-order') ||
  !ordersWxml.includes('previewItems') ||
  !ordersWxml.includes('cover-order')
)
  errors.push('订单页必须同时支持门店商品订单与储值/礼品卡订单卡片');
if (!ordersWxml.includes('empty-state') || !ordersWxml.includes('filteredOrders.length'))
  errors.push('订单页必须为空分类结果提供空状态');
if (!ordersWxml.includes('tabbar-safe-space')) errors.push('订单页列表底部必须保留 TabBar 安全区');
if (!ordersWxss.includes('height: 100vh') || !ordersWxss.includes('min-height: 0'))
  errors.push('订单页必须使用视口高度的纵向 flex 骨架与自适应滚动区');
if (!/\.order-card__status-wrap\.is-canceled/.test(ordersWxss)) errors.push('订单页必须区分已完成与已取消状态样式');
if (/#[0-9A-Fa-f]{3,8}\b|rgba?\(/.test(ordersWxss))
  errors.push('订单页 WXSS 必须使用设计 token，禁止出现颜色字面量与 rgba()');
const designDocPath = path.join(root, 'docs/design-system.md');
if (!fs.existsSync(designDocPath)) {
  errors.push('缺少 UI 设计规范文档: docs/design-system.md');
}

const appWxss = fs.readFileSync(path.join(root, 'app.wxss'), 'utf8');
const requiredTokens = [
  '--brand-tint',
  '--page-bg-neutral',
  '--text-muted',
  '--dark-fill',
  '--price-red',
  '--tag-hot-bg',
  '--tag-light-bg',
  '--tag-light-text',
  '--font-caption',
  '--font-sm',
  '--font-base',
  '--font-md',
  '--font-lg',
  '--font-xl',
  '--font-display',
  '--radius-xs',
  '--radius-sm',
  '--radius-md',
  '--radius-lg',
  '--radius-pill',
  '--shadow-card',
  '--shadow-float'
];
for (const token of requiredTokens) {
  if (!new RegExp(`${token}\\s*:`).test(appWxss)) errors.push(`app.wxss 缺少设计 token: ${token}`);
}
const tokenNames = [...appWxss.matchAll(/(--[a-z0-9-]+)\s*:/g)].map(match => match[1]);
const duplicatedTokens = [...new Set(tokenNames.filter((name, index) => tokenNames.indexOf(name) !== index))];
if (duplicatedTokens.length) errors.push(`app.wxss 存在重复的 token 定义: ${duplicatedTokens.join(', ')}`);

const agentsPath = path.join(root, '..', 'AGENTS.md');
if (fs.existsSync(agentsPath)) {
  const agentsSource = fs.readFileSync(agentsPath, 'utf8');
  if (!agentsSource.includes('## UI 设计规范') || !agentsSource.includes('docs/design-system.md')) {
    errors.push('AGENTS.md 必须包含 UI 设计规范章节并指向 docs/design-system.md');
  }
}
const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8');
if (menuWxml.includes('cartCount > 0 && !cartVisible')) {
  errors.push('购物车条在弹层打开时必须保持显示，禁止再使用 !cartVisible 条件');
}
const cartBarWxssSource = fs.readFileSync(path.join(root, 'components/cart-bar/cart-bar.wxss'), 'utf8');
const cartBarZIndex = cartBarWxssSource.match(/\.cart-bar-dock\s*\{[\s\S]*?z-index:\s*(\d+)/);
if (!cartBarZIndex || Number(cartBarZIndex[1]) < 1200) {
  errors.push('购物车条衬底 z-index 必须不小于 1200，确保弹层打开时浮在遮罩之上');
}
const cartBarWxmlSource = fs.readFileSync(path.join(root, 'components/cart-bar/cart-bar.wxml'), 'utf8');
if (
  !cartBarWxmlSource.includes('cart-bar-dock') ||
  !cartBarWxmlSource.includes('shopping-bag-white.svg') ||
  !cartBarWxmlSource.includes('去结算')
) {
  errors.push('购物车条必须保留白色衬底、购物袋图标与结算按钮结构');
}
const cartSheetWxml = fs.readFileSync(path.join(root, 'components/cart-sheet/cart-sheet.wxml'), 'utf8');
if (cartSheetWxml.includes('cart-sheet__summary')) {
  errors.push('购物车弹层不得保留重复的底部结算条');
}
const tabBarWxml = fs.readFileSync(path.join(root, 'custom-tab-bar/index.wxml'), 'utf8');
const tabBarJs = fs.readFileSync(path.join(root, 'custom-tab-bar/index.js'), 'utf8');
const tabPaths = [...tabBarJs.matchAll(/pagePath:\s*'([^']+)'/g)].map(match => match[1]);
if (
  tabPaths.length !== 5 ||
  ![
    '/pages/home/home',
    '/pages/menu/menu',
    '/pages/member/member',
    '/pages/orders/orders',
    '/pages/profile/profile'
  ].every((item, index) => item === tabPaths[index])
) {
  errors.push('底部导航五个页面路径必须与 Tab 页面逐项一致');
}
if (
  !tabBarWxml.includes('open-type="switchTab"') ||
  !tabBarWxml.includes('url="{{item.pagePath}}"') ||
  tabBarWxml.includes('bindtap="switchTab"')
) {
  errors.push('底部导航必须使用原生 switchTab 跳转');
}
if (!/z-index:\s*10000/.test(fs.readFileSync(path.join(root, 'custom-tab-bar/index.wxss'), 'utf8'))) {
  errors.push('底部导航必须保持在页面最高交互层级');
}

for (const file of sourceFiles.filter(file => file.endsWith('.json'))) {
  try {
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    for (const componentPath of Object.values(json.usingComponents || {})) {
      const componentBase = componentPath.startsWith('/') ? componentPath.slice(1) : componentPath;
      if (!fs.existsSync(path.join(root, `${componentBase}.wxml`))) {
        errors.push(`组件不存在: ${componentPath} (${path.relative(root, file)})`);
      }
    }
  } catch (error) {
    errors.push(`JSON 无法解析: ${path.relative(root, file)} - ${error.message}`);
  }
}

if (errors.length) {
  console.error(`项目结构校验失败，共 ${errors.length} 项:\n- ${errors.join('\n- ')}`);
  process.exit(1);
}

console.log(
  `项目结构校验通过: ${expectedPages.length} 个页面、${tabBarPages.length} 个 Tab，Lucide 图标与本地资源完整。`
);

