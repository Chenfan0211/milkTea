import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appJs = fs.readFileSync(path.join(root, 'app.js'), 'utf8');
const menuWxml = fs.readFileSync(path.join(root, 'pages/menu/menu.wxml'), 'utf8');
const menuJs = fs.readFileSync(path.join(root, 'pages/menu/menu.js'), 'utf8');
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));

if (!appJs.includes('onHide()') || !appJs.includes('handleAppHide')) {
  throw new Error('点单页必须由 App.onHide 记录退出时间');
}
// 点单页在 Skyline 下不内嵌 <map>（会白屏），也不保留「假地图」静态占位图：
// 门店导航统一交给 wx.openLocation 拉起真实腾讯地图（见 store-list-card 导航按钮）。
if (menuWxml.includes('store-picker__map--entry')) {
  throw new Error('点单页不得保留假地图占位入口，应改为门店列表直出');
}
if (!menuWxml.includes('pickerStores') || !menuWxml.includes('bind:navigate=')) {
  throw new Error('点单页必须包含门店选择层与门店导航入口');
}
{
  const mapJsonPath = path.join(root, 'pages/store-map/store-map.json');
  if (!fs.existsSync(mapJsonPath)) throw new Error('缺少独立门店地图页');
  const mapJson = JSON.parse(fs.readFileSync(mapJsonPath, 'utf8'));
  if (mapJson.renderer !== 'webview') throw new Error('独立地图页必须使用 webview 渲染器，否则地图不显示');
  const mapWxml = fs.readFileSync(path.join(root, 'pages/store-map/store-map.wxml'), 'utf8');
  if (!mapWxml.includes('<map') || !mapWxml.includes('markers="{{markers}}"')) {
    throw new Error('独立地图页必须渲染地图与 marker');
  }
}
if (
  !menuJs.includes('resolveStoreCatalog') ||
  !menuJs.includes('selectStore') ||
  !menuJs.includes('useDeviceLocation')
) {
  throw new Error('点单页必须接入门店会话与模拟定位');
}
if (!appJson.pages.includes('pages/city-picker/city-picker')) {
  throw new Error('app.json 必须注册城市选择页');
}
for (const extension of ['js', 'json', 'wxml', 'wxss']) {
  if (!fs.existsSync(path.join(root, `pages/city-picker/city-picker.${extension}`))) {
    throw new Error(`缺少城市选择页文件: ${extension}`);
  }
}

console.log('点单门店选择层与城市页结构测试通过');

const require = createRequire(import.meta.url);
const storage = new Map();
let capturedPage = null;
let lastNavigatedTo = '';

globalThis.wx = {
  getStorageSync(key) {
    return storage.has(key) ? storage.get(key) : '';
  },
  setStorageSync(key, value) {
    storage.set(key, value);
  },
  removeStorageSync(key) {
    storage.delete(key);
  },
  showToast() {},
  makePhoneCall() {},
  openLocation() {},
  switchTab() {},
  navigateTo({ url }) {
    lastNavigatedTo = url;
  },
  navigateBack() {}
};
// 门店 / 城市 / 菜单改为接口获取：从 DB seed 解析后经 wx.request 返回
const migrationDir = path.join(root, '..', 'server/src/main/resources/db/migration');
const baseSeed = fs.readFileSync(path.join(migrationDir, 'V3__seed_base.sql'), 'utf8');
const appConfigSeed = fs.readFileSync(path.join(migrationDir, 'V10__app_config.sql'), 'utf8');
const citiesMatch = appConfigSeed.match(/'app_cities'[\s\S]*?\[([\s\S]*?)\]'/);
const seedCities = JSON.parse('[' + citiesMatch[1].replace(/\n/g, '') + ']');
const subjectBlock = baseSeed.match(/INSERT INTO biz_subject[\s\S]*?;/);
const SUBJECT_NAMES = {};
for (const m of subjectBlock[0].matchAll(/\((\d+)\s*,\s*'[^']*'\s*,\s*'([^']+)'\s*,\s*'STORE'/g)) SUBJECT_NAMES[Number(m[1])] = m[2];
const profileBlock = baseSeed.match(/INSERT INTO store_profile \([\s\S]*?;/);
const STORE_CODES = { 101: 'store-001', 102: 'store-002', 103: 'store-003', 104: 'store-004', 105: 'store-005' };
const CITY_BY_NAME = { 长沙市: 'changsha', 广州市: 'guangzhou', 深圳市: 'shenzhen' };
// 菜单：从 V4 seed 解析 tab/group/category/product 层级
const productSeed = fs.readFileSync(path.join(migrationDir, 'V4__seed_product.sql'), 'utf8');
const catBlock = productSeed.match(/INSERT INTO product_category[\s\S]*?;/);
const CATS = [...catBlock[0].matchAll(/\((\d+),\s*(\d+),\s*'([^']+)',\s*'([^']+)',\s*'(TAB|GROUP|CATEGORY)',\s*\d+\)/g)].map(m => ({
  id: Number(m[1]), parentId: Number(m[2]), code: m[3], name: m[4], type: m[5]
}));
const prodBlock = productSeed.match(/INSERT INTO product \(id, product_id[\s\S]*?;/);
const PRODS = [...prodBlock[0].matchAll(/\((\d+),\s*'([^']+)',\s*'[^']+',\s*'([^']+)',\s*(\d+),/g)].map(m => ({
  id: m[2], name: m[3], categoryId: Number(m[4]), price: 1390, originalPrice: 1600, storedValuePrice: 1290,
  image: '/assets/images/3x/menu-product.jpg', tags: [], description: ''
}));
const seedMenu = CATS.filter(c => c.type === 'TAB').map(tab => ({
  id: tab.code, label: tab.name,
  groups: CATS.filter(g => g.type === 'GROUP' && g.parentId === tab.id).map(g => ({
    id: g.code, label: g.name,
    categories: CATS.filter(c => c.type === 'CATEGORY' && c.parentId === g.id).map(c => ({
      id: c.code, label: c.name,
      products: PRODS.filter(p => p.categoryId === c.id)
    }))
  }))
}));

const seedStores = [...profileBlock[0].matchAll(/\((\d+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*([\d.]+),\s*([\d.]+),\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*(\d+|NULL),\s*'([^']+)',\s*'([^']*)',\s*'([^']*)',\s*(\d+)\)/g)].map(m => ({
  id: STORE_CODES[Number(m[1])],
  code: STORE_CODES[Number(m[1])],
  name: SUBJECT_NAMES[Number(m[1])] || '',
  city: m[2],
  cityCode: CITY_BY_NAME[m[2]] || 'changsha',
  address: m[3],
  phone: m[4],
  latitude: Number(m[5]),
  longitude: Number(m[6]),
  storeType: m[7],
  businessStatus: m[8],
  manager: m[9],
  businessHours: m[11],
  modes: JSON.parse(m[12]),
  promotion: m[13],
  queueCount: Number(m[14])
}));
globalThis.wx.request = function request(options) {
  const url = String(options.url || '');
  let data = [];
  if (url.includes('/app/stores')) data = seedStores;
  else if (url.includes('/config/cities')) data = seedCities;
  else if (url.includes('/app/menu')) data = seedMenu;
  else if (url.includes('/config/home')) data = {};
  setTimeout(() => options.success({ statusCode: 200, data: { code: 0, data, message: 'ok' } }), 0);
};

globalThis.getApp = () => ({
  globalData: {
    menuTabId: 'classic',
    orderMode: 'pickup',
    selectedStoreId: null,
    selectedCityCode: 'changsha',
    selectedCityName: '长沙市'
  }
});
globalThis.Page = config => {
  capturedPage = config;
};

function createPage(config) {
  const instance = Object.assign({}, config);
  instance.data = JSON.parse(JSON.stringify(config.data));
  instance.setData = updates => Object.assign(instance.data, updates);
  instance.tabBarState = { selected: 0, hidden: false };
  instance.getTabBar = () => ({ setData: updates => Object.assign(instance.tabBarState, updates) });
  return instance;
}

require(path.join(root, 'pages/menu/menu.js'));
const menuPage = createPage(capturedPage);
// 门店/菜单改为接口异步加载：等待完成后再断言
menuPage.onLoad();
await new Promise(resolve => setTimeout(resolve, 30));
if (!menuPage.data.storePickerVisible || !menuPage.data.pickerStores.length || menuPage.data.pickerHasCurrentStore) {
  throw new Error('点单页首次无缓存时必须展示门店选择层且不预选门店');
}
menuPage.openFavoriteStores();
if (lastNavigatedTo !== '/pages/favorite-stores/favorite-stores') {
  throw new Error('顶部收藏必须打开收藏门店列表');
}
menuPage.handleSelectPickerStore({
  detail: { store: menuPage.data.pickerStores.find(store => store.id === 'store-002') }
});
if (menuPage.data.currentStore.id !== 'store-002' || menuPage.data.storePickerVisible) {
  throw new Error('点击门店后必须保存当前门店并关闭选择层');
}
menuPage.handleCurrentFavorite();
if (!menuPage.data.currentStore.isFavorite) {
  throw new Error('点击灰色五角星必须收藏当前门店');
}
menuPage.handleCurrentFavorite();
if (menuPage.data.currentStore.isFavorite) {
  throw new Error('再次点击金色五角星必须取消收藏');
}
const cachedMenuPage = createPage(capturedPage);
cachedMenuPage.onLoad();
cachedMenuPage.onShow();
if (cachedMenuPage.data.storePickerVisible || cachedMenuPage.data.currentStore.id !== 'store-002') {
  throw new Error('存在有效门店缓存时必须直接进入菜单');
}
cachedMenuPage.selectStore();
if (!cachedMenuPage.data.storePickerVisible) {
  throw new Error('主动点击门店行时必须重新打开选择层');
}
// 购物车初始为空（商品来自菜单接口），这里注入一条用于验证规格编辑流程
const initialCartItem = {
  id: 'classic-005-medium-standard-ice',
  productId: 'classic-005',
  selectedOptionIds: ['medium', 'standard-ice'],
  name: '红苹果乌龙冰奶',
  spec: '中杯,标准冰',
  price: 14.9,
  originalPrice: 16,
  quantity: 1,
  selected: true,
  listed: true
};
cachedMenuPage.setData({ cartItems: [initialCartItem] });
cachedMenuPage.setData({ specMode: 'edit', editingCartItemId: initialCartItem.id });
cachedMenuPage.getTabBar().setData({ hidden: true });
cachedMenuPage.closeSpec();
if (cachedMenuPage.tabBarState.hidden) {
  throw new Error('取消规格编辑后必须恢复底部 TabBar');
}
cachedMenuPage.setData({ specMode: 'edit', editingCartItemId: initialCartItem.id });
cachedMenuPage.getTabBar().setData({ hidden: true });
cachedMenuPage.handleCartUpdate({
  detail: {
    product: { id: initialCartItem.productId, name: initialCartItem.name },
    selectedOptions: initialCartItem.selectedOptionIds.map(id => ({ id, label: id, priceDelta: 0 })),
    quantity: initialCartItem.quantity,
    unitPrice: initialCartItem.price,
    originalPrice: initialCartItem.originalPrice,
    specText: initialCartItem.spec
  }
});
if (cachedMenuPage.tabBarState.hidden) {
  throw new Error('确认规格修改后必须恢复底部 TabBar');
}

capturedPage = null;
require(path.join(root, 'pages/city-picker/city-picker.js'));
const cityPage = createPage(capturedPage);
cityPage.onLoad({ city: 'changsha' });
cityPage.handleSelectCity({ currentTarget: { dataset: { code: 'guangzhou' } } });
const { resolveStoreCatalog } = require(path.join(root, 'utils/store.js'));
const catalog = resolveStoreCatalog();
if (catalog.city.code !== 'guangzhou' || catalog.currentStore) {
  throw new Error('切换城市后必须进入新城市且不自动预选门店');
}

console.log('点单选店与切城交互测试通过');
