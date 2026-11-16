import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const pageBase = path.join(root, 'pages/home/home');
const storage = {};

globalThis.wx = {
  getStorageSync(key) {
    return storage[key] || '';
  },
  setStorageSync(key, value) {
    storage[key] = value;
  },
  removeStorageSync(key) {
    delete storage[key];
  },
  showToast() {},
  navigateTo() {},
  switchTab() {},
  showShareMenu() {}
};

let definition = null;
globalThis.Page = page => {
  definition = page;
};

const api = require(path.join(root, 'utils/api.js'));
api.fetchHomeConfig = () => Promise.resolve({ shortcuts: [], hero: {} });
api.resolveAssetUrl = value =>
  String(value || '').indexOf('/api/') === 0 ? 'https://api.example.com' + value : String(value || '');
api.fetchMe = () => Promise.reject(new Error('not logged in'));

require(`${pageBase}.js`);

function createPage() {
  const page = Object.assign({}, definition);
  page.data = Object.assign({}, definition.data);
  page.setData = function setData(updates) {
    this.data = Object.assign({}, this.data, updates);
  };
  page.getTabBar = () => ({ setData() {} });
  return page;
}

const remoteHero = createPage();
api.fetchHomeConfig = () =>
  Promise.resolve({
    shortcuts: [],
    hero: {
      imageUrl: '/api/v1/files/public/abc123.jpg',
      altText: '后台配置的首页活动图'
    }
  });
await definition.onShow.call(remoteHero);
assert.equal(
  remoteHero.data.homeHeroUrl,
  'https://api.example.com/api/v1/files/public/abc123.jpg',
  '后台相对 URL 必须补全 API 域名后渲染'
);
assert.equal(remoteHero.data.homeHeroAlt, '后台配置的首页活动图', '后台说明文案必须生效');
assert.equal(remoteHero.data.homeBanners.length, 1, '旧单图配置必须变成一张轮播图');

const multiHero = createPage();
api.fetchHomeConfig = () =>
  Promise.resolve({
    hero: {
      banners: [
        { id: 'first', imageUrl: '/first.jpg', altText: '第一张', targetType: 'mallProduct', targetId: '27' },
        { id: 'second', imageUrl: '/second.jpg', altText: '第二张', targetType: 'coupons' }
      ]
    }
  });
await definition.onShow.call(multiHero);
assert.deepEqual(
  multiHero.data.homeBanners.map(item => item.id),
  ['first', 'second']
);
assert.equal(multiHero.data.homeBanners[0].targetType, 'mallProduct');

const emptyHero = createPage();
api.fetchHomeConfig = () => Promise.resolve({ shortcuts: [], hero: { imageUrl: '', altText: '' } });
await definition.onShow.call(emptyHero);
assert.equal(emptyHero.data.homeHeroUrl, '/assets/images/3x/home-hero.jpg', '空配置必须回退静态默认图');
assert.equal(emptyHero.data.homeHeroAlt, '金秋入茶限定上新', '空说明必须回退默认说明');

const inactiveHero = createPage();
api.fetchHomeConfig = () =>
  Promise.resolve({
    hero: {
      banners: [
        { id: 'disabled', imageUrl: '/disabled.jpg', status: 'disabled', targetType: 'coupons' },
        { id: 'deleted', imageUrl: '/deleted.jpg', status: 'enabled', deleted: 1, targetType: 'orders' }
      ]
    }
  });
await definition.onShow.call(inactiveHero);
assert.deepEqual(
  inactiveHero.data.homeBanners,
  [
    {
      id: 'default-home-hero',
      imageUrl: '/assets/images/3x/home-hero.jpg',
      altText: '金秋入茶限定上新',
      targetType: 'none',
      targetId: ''
    }
  ],
  '全部停用或删除时只显示无跳转默认图'
);
assert.equal(inactiveHero.data.homeHeroUrl, '/assets/images/3x/home-hero.jpg');

const failedHero = createPage();
failedHero.setData({ homeHeroUrl: 'https://cdn.example.com/failed.jpg', homeHeroAlt: '失败图' });
definition.handleHeroError.call(failedHero);
assert.equal(failedHero.data.homeHeroUrl, '/assets/images/3x/home-hero.jpg', '图片加载失败必须回退静态默认图');
assert.equal(failedHero.data.homeHeroAlt, '金秋入茶限定上新', '图片加载失败必须回退默认说明');

const wxml = fs.readFileSync(`${pageBase}.wxml`, 'utf8');
assert.ok(wxml.includes('<swiper'), '首页主视觉必须支持轮播');
assert.ok(wxml.includes('wx:for="{{homeBanners}}"'), '首页主视觉必须逐图展示');
assert.ok(wxml.includes('mode="aspectFit"'), '图片必须等比完整展示');
assert.ok(wxml.includes('binderror="handleHeroError"'), '首页主视觉必须绑定加载失败处理');
assert.ok(wxml.includes('wx:if="{{item.targetType !== \'none\'}}"'), '有跳转目标的轮播才具有点击入口');
assert.ok(wxml.includes('wx:else'), '无跳转目标的默认图必须使用不可点击图片');

console.log('home-hero-config.test.mjs: passed');
