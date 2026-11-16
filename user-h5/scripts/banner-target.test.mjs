import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const targets = require(path.join(root, 'utils/banner-target.js'));
const homeHero = require(path.join(root, 'utils/home-hero.js'));

const resolve = value => (value.startsWith('/api/') ? `https://api.example.com${value}` : value);
assert.deepEqual(homeHero.normalizeHomeBanners({ imageUrl: '/old.jpg', altText: '旧图' }, resolve), [
  { id: 'legacy', imageUrl: '/old.jpg', altText: '旧图', targetType: 'none', targetId: '' }
]);
assert.deepEqual(
  homeHero.normalizeHomeBanners(
    {
      banners: [
        { id: 'a', imageUrl: '/api/new.jpg', targetType: 'mallProduct', targetId: '27' },
        { id: 'b', imageUrl: '' }
      ]
    },
    resolve
  ),
  [
    {
      id: 'a',
      imageUrl: 'https://api.example.com/api/new.jpg',
      altText: '首页活动图',
      targetType: 'mallProduct',
      targetId: '27'
    }
  ]
);
assert.equal(homeHero.normalizeHomeBanners({ banners: [] }, resolve).length, 1, '空配置回退默认图');

assert.deepEqual(targets.normalizeTarget({ targetType: 'mallProduct', targetId: '27' }), {
  targetType: 'mallProduct',
  targetId: '27'
});
assert.deepEqual(
  targets.normalizeTarget({ targetType: 'https://bad.example', targetId: '/pages/orders/orders' }),
  {
    targetType: 'none',
    targetId: ''
  },
  '外部路径不得成为跳转目标'
);
assert.deepEqual(
  targets.normalizeTarget({ targetType: 'mallProduct', targetId: '../bad' }),
  {
    targetType: 'none',
    targetId: ''
  },
  '商城商品只能使用数字 ID'
);
assert.equal(targets.isMallProductAvailable({ id: 27, soldOut: false, availableStock: 1 }), true);
assert.equal(targets.isMallProductAvailable({ id: 27, soldOut: true, availableStock: 0 }), false);
assert.equal(targets.isMallProductAvailable({ id: 27, soldOut: false, availableStock: 0 }), false);

const menu = [{ groups: [{ categories: [{ products: [{ id: 'tea-1', name: '茶一' }] }] }] }];
assert.equal(targets.findMenuProduct(menu, 'tea-1').name, '茶一');
assert.equal(targets.findMenuProduct(menu, 'tea-2'), null);

const stores = [
  { id: 'far', distanceValue: 8 },
  { id: 'near', distanceValue: 1 }
];
assert.equal(targets.pickPointStore({ stores, currentStore: stores[0], location: { source: 'device' } }).id, 'near');
assert.equal(targets.pickPointStore({ stores, currentStore: stores[0], location: { source: 'default' } }).id, 'far');

const routes = [];
const toasts = [];
const app = { globalData: {} };
globalThis.getApp = () => app;
globalThis.wx = { showToast: value => toasts.push(value) };
const navigate = require(path.join(root, 'utils/navigate.js'));
const api = require(path.join(root, 'utils/api.js'));
const store = require(path.join(root, 'utils/store.js'));
const loginGuard = require(path.join(root, 'utils/login-guard.js'));
navigate.open = url => routes.push(url);
loginGuard.requireLogin = action => action();
await targets.openBannerTarget(homeHero.DEFAULT_HOME_BANNERS[0]);
assert.deepEqual(routes, [], '首页默认图不得跳转');
assert.deepEqual(toasts, [], '首页默认图点击不得弹提示');
await targets.openBannerTarget({ targetType: 'coupons' });
assert.equal(routes.at(-1), '/pages/coupon-list/coupon-list');
api.fetchMallProductDetail = async () => ({ id: 27, soldOut: false, availableStock: 2 });
await targets.openBannerTarget({ targetType: 'mallProduct', targetId: '27' });
assert.equal(routes.at(-1), '/packageMall/pages/product-detail/product-detail?id=27');
api.fetchMallProductDetail = async () => ({ id: 27, soldOut: true, availableStock: 0 });
await targets.openBannerTarget({ targetType: 'mallProduct', targetId: '27' });
assert.equal(routes.at(-1), '/packageMall/pages/home/home', '售罄时留在商城列表');
assert.equal(toasts.at(-1).icon, 'none');
api.fetchMallProductDetail = async () => {
  throw new Error('已下架');
};
await targets.openBannerTarget({ targetType: 'mallProduct', targetId: '27' });
assert.equal(routes.at(-1), '/packageMall/pages/home/home', '下架时留在商城列表');

let selectedStore = '';
store.refreshCitiesFromRemote = async () => [];
store.refreshStoreCatalogFromRemote = async () => [];
store.resolveStoreCatalog = () => ({
  stores: [
    { id: 'far', distanceValue: 8 },
    { id: 'near', distanceValue: 1 }
  ],
  location: { source: 'device' }
});
store.selectStore = id => {
  selectedStore = id;
};
await targets.openBannerTarget({ targetType: 'menuProduct', targetId: 'tea-1' });
assert.equal(selectedStore, 'near');
assert.equal(app.globalData.pendingBannerProductId, 'tea-1');
assert.equal(routes.at(-1), '/pages/menu/menu');

console.log('banner-target.test.mjs: passed');
