import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const componentRoot = path.join(root, 'components/store-list-card/store-list-card');
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  assert.ok(fs.existsSync(`${componentRoot}.${extension}`), `缺少门店列表卡文件: store-list-card.${extension}`);
}

const componentWxml = fs.readFileSync(`${componentRoot}.wxml`, 'utf8');
const componentWxss = fs.readFileSync(`${componentRoot}.wxss`, 'utf8');
const componentJs = fs.readFileSync(`${componentRoot}.js`, 'utf8');
for (const content of [
  'store.promotion',
  'store.name',
  'store.distanceLabel',
  'store.statusText',
  'store.address',
  'store.businessHours',
  'leaf-white.svg',
  'phone-white.svg',
  'navigation-white.svg',
  'store-decor-sprout.svg',
  'star-gold.svg'
]) {
  assert.ok(componentWxml.includes(content), `公共门店卡缺少内容: ${content}`);
}
assert.ok(
  componentWxml.includes('catchtap="handlePhone"') && componentWxml.includes('catchtap="handleNavigate"'),
  '电话和导航必须阻止卡片选择事件冒泡'
);
assert.ok(
  componentWxml.includes('favoriteInteractive') && componentWxml.includes('catchtap="handleFavorite"'),
  '收藏页必须支持点击取消收藏'
);
assert.ok(
  componentWxml.includes('aria-label="拨打{{store.name}}电话"') &&
    componentWxml.includes('aria-label="导航到{{store.name}}"'),
  '门店操作必须提供无障碍标签'
);
assert.ok(
  componentJs.includes('selected: { type: Boolean') &&
    componentJs.includes('favoriteInteractive: { type: Boolean') &&
    componentJs.includes('store: { type: Object'),
  '公共门店卡必须声明 selected、favoriteInteractive 和 store 属性'
);
assert.ok(
  componentJs.includes("triggerEvent('select'") &&
    componentJs.includes("triggerEvent('phone'") &&
    componentJs.includes("triggerEvent('navigate'") &&
    componentJs.includes("triggerEvent('favorite'"),
  '公共门店卡必须派发完整交互事件'
);
assert.ok(
  // 卡片改为内容撑高（不再用 min-height 兜底），并收紧内边距与间距，使一屏能展示更多门店
  !/\.store-list-card\s*\{[^}]*min-height/.test(componentWxss) &&
    /\.store-list-card\s*\{[^}]*padding:\s*16rpx 20rpx/.test(componentWxss),
  '公共门店卡必须由内容撑高并收紧内边距'
);
assert.ok(
  /\.store-list-card\s*\+[^{]*\{[^}]*margin-top:\s*20rpx/.test(componentWxss),
  '门店卡之间的间距必须收紧至 20rpx'
);
assert.ok(
  componentWxss.includes('background: var(--card-bg)') &&
    componentWxss.includes('linear-gradient(90deg, var(--store-promo-from), var(--store-promo-to))') &&
    componentWxss.includes('linear-gradient(180deg, var(--store-action-from), var(--store-action-to))') &&
    componentWxss.includes('background: var(--store-pill-bg)') &&
    componentWxss.includes('color: var(--store-card-title)') &&
    componentWxss.includes('color: var(--store-card-ink)') &&
    componentWxss.includes('color: var(--store-card-accent)') &&
    componentWxss.includes('border-radius: var(--radius-lg)'),
  '公共门店卡必须使用方案 D 的鲜绿 token'
);
assert.ok(!/\b\d+px\b/.test(componentWxss), '公共门店卡不得使用 px');
const colorLiterals = [
  ...new Set(
    [...componentWxss.matchAll(/#[0-9A-Fa-f]{3,8}\b/g)]
      .map(match => match[0])
      .filter(color => color.toUpperCase() !== '#FFFFFF')
  )
];
assert.deepEqual(colorLiterals, [], '公共门店卡不得写死品牌颜色');
assert.ok(
  // 状态行只承载营业/排队文案（如「现在下单，立即制作」），
  // 不得退回「可外卖 / 仅自提」这类取餐方式标签。
  componentWxml.includes('store.statusText') &&
    !componentWxml.includes('可外卖') &&
    !componentWxml.includes('仅自提'),
  '公共门店卡状态行不得显示可外卖或仅自提标签'
);
const storeMockSource = fs.readFileSync(path.join(root, 'data/mock.js'), 'utf8');
assert.ok(
  !storeMockSource.includes("statusText: '可外卖'") &&
    !storeMockSource.includes("statusText: '仅自提'"),
  '门店 Mock 不得继续保留可外卖或仅自提状态字段'
);

const pageConfigs = {
  menu: JSON.parse(fs.readFileSync(path.join(root, 'pages/menu/menu.json'), 'utf8')),
  couponStores: JSON.parse(fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.json'), 'utf8')),
  favoriteStores: JSON.parse(fs.readFileSync(path.join(root, 'pages/favorite-stores/favorite-stores.json'), 'utf8'))
};
for (const [name, config] of Object.entries(pageConfigs)) {
  assert.equal(
    config.usingComponents['store-list-card'],
    '/components/store-list-card/store-list-card',
    `${name} 必须注册公共门店卡`
  );
}
const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8');
const couponStoresWxml = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.wxml'), 'utf8');
const favoriteStoresWxml = fs.readFileSync(path.join(root, 'pages/favorite-stores/favorite-stores.wxml'), 'utf8');
assert.ok(
  menuWxml.includes('<store-list-card') &&
    menuWxml.includes('bind:select="handleSelectPickerStore"') &&
    menuWxml.includes('bind:phone="handleStorePhone"'),
  '点单门店层必须使用公共门店卡'
);
assert.ok(
  couponStoresWxml.includes('<store-list-card') &&
    couponStoresWxml.includes('bind:select="handleSelectStore"') &&
    couponStoresWxml.includes('bind:phone="handlePhone"'),
  '适用门店页必须使用公共门店卡'
);
assert.ok(
  favoriteStoresWxml.includes('<store-list-card') &&
    favoriteStoresWxml.includes('favorite-interactive="{{true}}"') &&
    favoriteStoresWxml.includes('bind:favorite="handleRemoveFavorite"'),
  '收藏门店页必须使用公共门店卡和收藏交互'
);
assert.ok(
  !menuWxml.includes('class="picker-store') &&
    !couponStoresWxml.includes('class="store-card') &&
    !favoriteStoresWxml.includes('class="favorite-store'),
  '页面不得继续保留重复门店卡结构'
);

assert.ok(
  !componentWxml.includes('check.svg') && !componentWxml.includes('store-list-card__selected'),
  '公共门店卡不得再显示右上角勾选'
);
assert.ok(!componentWxss.includes('.store-list-card__selected'), '公共门店卡不得保留勾选样式');
assert.ok(
  /\.store-list-card__body\s*\{[^}]*padding-right:\s*0/.test(componentWxss),
  '门店卡顶部内容不得为勾选预留右侧空间'
);
assert.ok(
  /\.store-list-card__distance-wrap\s*\{[^}]*margin-left:\s*auto[^}]*flex:\s*none/.test(componentWxss),
  '门店距离必须固定靠右展示'
);
assert.ok(
  componentWxml.includes('store-list-card__decor') &&
    componentWxml.includes('/assets/icons/lucide/store-decor-sprout.svg') &&
    /\.store-list-card__decor\s*\{[^}]*position:\s*absolute/.test(componentWxss) &&
    /\.store-list-card__decor\s*\{[^}]*right:\s*8rpx/.test(componentWxss) &&
    /\.store-list-card__decor\s*\{[^}]*bottom:\s*-48rpx/.test(componentWxss) &&
    /\.store-list-card__decor\s*\{[^}]*width:\s*160rpx[^}]*height:\s*160rpx/.test(componentWxss),
  '公共门店卡必须按图二在右下角放置 Lucide 叶芽水印并被底边裁切'
);

const markerUtilPath = path.join(root, 'utils/store-markers.js');
assert.ok(fs.existsSync(markerUtilPath), '必须提供可测试的地图 marker 生成工具');
const { buildStoreMarkers } = require(markerUtilPath);
const markerStores = [
  { id: 'store-001', name: '星沙乐运魔方店', latitude: 28.24, longitude: 113.07 },
  { id: 'store-002', name: '松雅湖吾悦广场店', latitude: 28.23, longitude: 113.08 }
];
const markers = buildStoreMarkers(markerStores, 'store-002');
assert.equal(markers.length, 2, '每条门店数据必须生成一个地图 marker');
assert.ok(
  markers.every(marker => marker.callout.content && marker.callout.display === 'ALWAYS'),
  '每个地图 marker 必须常显门店名称'
);
assert.equal(markers[0].iconPath, '/assets/icons/lucide/store-map-pin.svg', '普通门店必须使用品牌绿定位针');
assert.equal(markers[1].iconPath, '/assets/icons/lucide/store-map-pin-active.svg', '当前门店必须使用深色定位针');
assert.ok(
  markers.every(marker => marker.width === 40 && marker.height === 40),
  '地图定位针尺寸必须为 40'
);
assert.deepEqual(markers[0].anchor, { x: 0.5, y: 1 }, '地图定位针必须底部指向门店坐标');
assert.ok(
  markers.every(marker => marker.zIndex === (marker.storeId === 'store-002' ? 3 : 2)),
  '当前门店 marker 必须位于上层'
);

const iconScript = fs.readFileSync(path.join(root, 'scripts/sync-lucide-icons.mjs'), 'utf8');
assert.ok(
  iconScript.includes("output: 'store-map-pin'") && iconScript.includes("output: 'store-map-pin-active'"),
  '图标映射必须生成普通与当前门店定位针'
);
assert.ok(
  !iconScript.includes("output: 'store-marker'") && !iconScript.includes("output: 'store-marker-active'"),
  '图标映射不得继续生成旧杯子 marker'
);
assert.ok(
  fs.existsSync(path.join(root, 'assets/icons/lucide/store-map-pin.svg')) &&
    fs.existsSync(path.join(root, 'assets/icons/lucide/store-map-pin-active.svg')),
  '缺少定位针 marker 运行资源'
);

console.log('公共门店列表卡与地图 marker 测试通过');
