import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const homeDir = path.join(root, 'packageMall', 'pages', 'home');
const read = name => fs.readFileSync(path.join(homeDir, `${name}`), 'utf8');
const wxml = read('home.wxml');
const wxss = read('home.wxss');
const js = read('home.js');
// 页面复用 packageMall/styles/mall.wxss 的 .mall-page / .mall-scroll 骨架，
// 固定区与滚动区的 flex 链依赖这两个类，需一并校验。
const sharedWxss = fs.readFileSync(path.join(root, 'packageMall', 'styles', 'mall.wxss'), 'utf8');

assert.match(wxml, /五零时光/, '头图必须保留时光币同款水印');
assert.match(wxml, /把好物带回家/, '头图主标题必须保留');
assert.match(wxml, /全场包邮/, '头图说明必须保留');
assert.match(wxml, /该分类暂无商品/, '分类无商品时必须有明确空态');
assert.match(wxml, /刷新看看/, '空态必须提供刷新操作');
assert.match(wxml, /暂时无货/, '售罄商品必须显示暂时无货');
assert.match(wxml, /剩余 /, '有货商品必须显示剩余件数');
assert.match(wxml, /product-card__original/, '商品卡必须保留划线原价');
assert.match(wxml, /product-card__sold-out/, '售罄角标必须留在商品图上');
assert.doesNotMatch(
  wxml,
  /home-hero__badge|home-quick-card|home-section-head|商品分类|chevron/,
  '首页不得再出现白卡徽标、快捷卡、分类标题或箭头'
);
assert.match(
  wxss,
  /linear-gradient\(180deg,\s*var\(--brand-soft\),\s*var\(--card-bg\)\)/,
  '头图必须从品牌浅底过渡到白'
);
assert.match(wxss, /\.product-card\s*\{[^}]*border-radius:\s*var\(--radius-md\)/, '商品卡圆角必须使用 radius-md');
assert.doesNotMatch(wxss, /\.product-card\s*\{[^}]*border\s*:/, '商品卡不得再描边');
assert.doesNotMatch(wxss, /#[0-9A-Fa-f]{3,8}\b/, 'home.wxss 不得新增颜色字面量');

// ----------------------------------------------------------------
// 固定区只留头图和图文分类，商品独立滚动；订单在标题行，购物车悬浮
// ----------------------------------------------------------------
assert.match(wxml, /<navigation-bar\b[^>]*background="#E9F1D9"/, '导航栏底色必须与时光币商城相连');
assert.match(wxml, /<navigation-bar\b[^>]*\/>/, '导航栏只保留标题，不得继续承载订单入口');
assert.doesNotMatch(wxml, /slot="right"/, '订单不得继续占用导航栏右侧插槽');
assert.match(
  wxml,
  /class="home-hero__head"[\s\S]*?class="home-hero__title">把好物带回家[\s\S]*?bindtap="openOrders"[\s\S]*?aria-label="查看商城订单"[\s\S]*?receipt\.svg[\s\S]*?订单/,
  '订单必须放在头图标题同一行的最右侧，使用收据图标和「订单」二字'
);
assert.match(
  wxml,
  /class="home-hero__meta"[\s\S]*?全场包邮[\s\S]*?home-hero__watermark[\s\S]*?五零时光/,
  '水印必须移到副标题区域，避免与订单入口重叠'
);
assert.match(wxml, /<view class="home-fixed">/, '必须存在固定信息区 home-fixed');
assert.match(
  wxml,
  /class="home-fixed"[\s\S]*?home-hero[\s\S]*?category-scroll[\s\S]*?<\/view>\s*<scroll-view class="mall-scroll home-products-scroll"/,
  '固定区只保留头图和分类，商品列表在其之后独立滚动'
);
assert.match(
  wxml,
  /<scroll-view class="mall-scroll home-products-scroll"[\s\S]*?bindscrolltolower="loadMore"[\s\S]*?<\/scroll-view>\s*<view class="home-cart"/,
  '悬浮购物车必须盖在商品滚动区之外，不跟商品一起滚'
);
assert.match(
  wxml,
  /class="home-cart"[^>]*aria-label="打开购物车"[\s\S]*?shopping-bag\.svg/,
  '购物车必须是购物袋图标按钮'
);
assert.match(
  wxml,
  /wx:if="\{\{cartCount > 0\}\}"[\s\S]*?home-cart__badge[\s\S]*?\{\{cartCountText\}\}/,
  '件数大于 0 时必须显示角标'
);

assert.match(wxss, /\.home-fixed\s*\{[\s\S]*?flex:\s*none/, '固定信息区必须 flex: none');
assert.match(
  wxss,
  /\.home-products-scroll\s*\{[\s\S]*?flex:\s*1;[\s\S]*?min-height:\s*0/,
  '商品滚动区必须 flex: 1 且 min-height: 0'
);
assert.match(
  sharedWxss,
  /\.mall-page\s*\{[\s\S]*?height:\s*100vh/,
  '页面容器必须撑满视口高度，否则固定区与滚动区的 flex 链失效'
);
assert.match(
  wxss,
  /\.home-cart\s*\{[\s\S]*?position:\s*fixed;[\s\S]*?right:\s*20rpx;[\s\S]*?bottom:\s*calc\(32rpx \+ env\(safe-area-inset-bottom\)\);[\s\S]*?width:\s*96rpx;[\s\S]*?height:\s*96rpx;[\s\S]*?box-shadow:\s*var\(--shadow-float\)/,
  '购物车必须是右下角 96rpx 悬浮圆按钮'
);
assert.match(wxss, /\.home-cart__icon\s*\{[\s\S]*?width:\s*40rpx;[\s\S]*?height:\s*40rpx/, '购物车图标必须为 40rpx');
assert.match(
  wxss,
  /\.home-cart__badge\s*\{[\s\S]*?color:\s*var\(--card-bg\);[\s\S]*?background:\s*var\(--brand-green\)/,
  '角标必须是品牌绿底白字'
);
assert.match(wxss, /\.home-order__icon\s*\{[\s\S]*?width:\s*32rpx;[\s\S]*?height:\s*32rpx/, '订单图标必须为 32rpx');
assert.match(
  wxss,
  /\.home-order__label\s*\{[\s\S]*?color:\s*var\(--text-main\);[\s\S]*?font-size:\s*var\(--font-sm\)/,
  '订单文字必须使用正文色和小字号'
);
assert.match(
  wxss,
  /\.home-products-content\s*\{[\s\S]*?padding:\s*20rpx 20rpx 120rpx/,
  '商品列表底部必须留出 120rpx，避免被悬浮按钮挡住'
);

// 图文分类：图标在上、名称在下，一屏固定 4 项，超过后横向滑动。
assert.match(
  wxml,
  /class="category-tab__image"[\s\S]*?mode="aspectFill"[\s\S]*?class="category-tab__label"/,
  '分类必须图标在上、名称在下'
);
assert.match(wxml, /src="\{\{item\.iconImage\}\}"/, '真实分类必须渲染后台 iconImage');
assert.match(wxml, /binderror="handleCategoryIconError"/, '分类图加载失败必须触发本地占位图降级');
assert.match(
  wxml,
  /class="category-tab__image"[\s\S]*?data-id="\{\{item\.id\}\}"[\s\S]*?binderror="handleCategoryIconError"/,
  '分类图降级必须按分类 id 定位'
);
assert.match(
  wxss,
  /\.category-scroll\s*\{[\s\S]*?height:\s*144rpx;[\s\S]*?overflow:\s*hidden/,
  '分类横滑容器必须有 144rpx 显式高度，避免 Skyline 高度塌陷'
);
assert.match(
  wxss,
  /\.category-tab\s*\{[\s\S]*?flex:\s*none;[\s\S]*?width:\s*calc\(\(100vw - 88rpx\) \/ 4\)/,
  '分类项必须固定为每屏 4 项，不得被压缩'
);
assert.match(wxss, /\.category-tab:last-child\s*\{\s*margin-right:\s*0;/, '最后一个分类不得保留右边距');
const tabRule = wxss.match(/\.category-tab\s*\{[^}]*\}/);
assert.ok(tabRule, '必须定义图文分类 Tab');
assert.match(tabRule[0], /color:\s*var\(--text-secondary\)/, '分类默认色必须为次级文字');
assert.doesNotMatch(tabRule[0], /flex:\s*1/, '分类不得用 flex:1 把文字挤扁');
assert.match(wxss, /\.category-tab\.is-active\s*\{[^}]*color:\s*var\(--brand-green\)/, '选中分类必须为品牌绿字');
assert.match(wxss, /\.category-tab\.is-active\s*\{[^}]*font-weight:\s*600/, '选中分类必须加粗');
assert.match(
  wxss,
  /\.category-tab__image\s*\{[\s\S]*?width:\s*80rpx;[\s\S]*?height:\s*80rpx;[\s\S]*?border-radius:\s*var\(--radius-md\)/,
  '分类图标必须为 80rpx 圆角方图'
);
assert.match(
  wxss,
  /\.category-tab__label\s*\{[\s\S]*?margin-top:\s*8rpx;[\s\S]*?font-size:\s*var\(--font-sm\);[\s\S]*?text-overflow:\s*ellipsis;[\s\S]*?white-space:\s*nowrap/,
  '分类名称必须与图标间隔 8rpx，并保持单行省略'
);
assert.match(wxml, /scroll-x/, '分类超出时必须可以横向滚动');
assert.doesNotMatch(wxss, /\.category-tab\.is-active::after/, '图文分类不得继续使用文字下划线选中态');

assert.match(wxss, /\.home-products-content\s*\{[\s\S]*?padding:\s*20rpx/, '商品区顶部必须保留 20rpx 安全间距');
assert.doesNotMatch(wxss, /\.product-card\s*\{[\s\S]*?margin-top:\s*-\d/, '商品卡不得使用负外边距上移');

assert.match(wxml, /bindtap="selectCategory"/, '分类必须保留点击切换');
assert.match(
  wxml,
  /class="category-tab \{\{activeCategoryId === item\.id \? 'is-active' : ''\}\}"/,
  '分类激活态必须绑定 activeCategoryId'
);
assert.match(wxml, /bindscrolltolower="loadMore"/, '必须保留上拉分页加载');
assert.match(wxml, /home-empty-action/, '必须保留空态刷新入口');

assert.match(js, /selectCategory/, '分类切换逻辑必须保留');
assert.match(js, /Number\(event\.currentTarget\.dataset\.id\)/, '分类点击必须读取 dataset id');
assert.match(js, /fetchMallProducts\(\{ page, size: PAGE_SIZE, categoryId \}\)/, '分类筛选必须传 categoryId');
assert.match(
  js,
  /categories:\s*\[\{\s*id:\s*0,\s*name:\s*'全部',\s*iconImage:\s*mall\.categoryIconUrl\(''\)\s*\}\]/,
  '「全部」必须作为第一项并带本地占位图'
);
assert.match(js, /handleCategoryIconError\(/, '首页必须提供分类图标加载失败降级');
assert.match(js, /mall\.CATEGORY_PLACEHOLDER_IMAGE/, '分类图标降级必须使用统一占位图');
assert.match(js, /\/packageMall\/pages\/product-detail\/product-detail\?id=/, '商品卡必须进入详情页');
assert.match(wxml, /bindtap="openCart"/, '购物车必须保留点击');
assert.match(wxml, /bindtap="openOrders"/, '订单必须保留点击');
assert.match(js, /onShow\(\)\s*\{[\s\S]*?syncCartCount\(\)/, '回到首页时必须刷新购物车件数');
assert.match(js, /mallCart\.read\(\)/, '件数必须来自购物车存储');
assert.match(js, /item\.quantity/, '件数必须按 quantity 求和');
assert.match(js, /count > 99 \? '99\+'/, '超过 99 件必须显示 99+');

for (const match of wxml.matchAll(/<view\b[^>]*bindtap="[^"]+"[^>]*>/g)) {
  assert.match(match[0], /aria-role="button"/, `可点击 view 缺少 aria-role: ${match[0]}`);
  assert.match(match[0], /aria-label=/, `可点击 view 缺少 aria-label: ${match[0]}`);
}

assert.doesNotMatch(wxss, /\b\d+(?:\.\d+)?px\b/, 'home.wxss 必须使用 rpx');
const allowedSpacing = new Set([
  '0',
  '1',
  '4',
  '8',
  '12',
  '16',
  '20',
  '24',
  '32',
  '40',
  '52',
  '64',
  '80',
  '104',
  '120'
]);
for (const line of wxss.split('\n')) {
  if (!/^\s*(?:padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|top|right|bottom|left|gap)\s*:/.test(line)) continue;
  for (const match of line.matchAll(/(?<![\d.])(\d+)rpx/g)) {
    assert.ok(allowedSpacing.has(match[1]), `间距必须使用设计档位：${line.trim()}`);
  }
}

console.log('商城首页对齐时光币商城测试通过');
