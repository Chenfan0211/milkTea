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
if (!menuWxml.includes('<map') || !menuWxml.includes('store-picker') || !menuWxml.includes('pickerStores')) {
  throw new Error('点单页必须包含地图门店选择层');
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
menuPage.onLoad();
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
const initialCartItem = cachedMenuPage.data.cartItems[0];
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
