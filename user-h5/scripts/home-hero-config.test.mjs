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

const emptyHero = createPage();
api.fetchHomeConfig = () => Promise.resolve({ shortcuts: [], hero: { imageUrl: '', altText: '' } });
await definition.onShow.call(emptyHero);
assert.equal(emptyHero.data.homeHeroUrl, '/assets/images/3x/home-hero.jpg', '空配置必须回退静态默认图');
assert.equal(emptyHero.data.homeHeroAlt, '金秋入茶限定上新', '空说明必须回退默认说明');

const failedHero = createPage();
failedHero.setData({ homeHeroUrl: 'https://cdn.example.com/failed.jpg', homeHeroAlt: '失败图' });
definition.handleHeroError.call(failedHero);
assert.equal(failedHero.data.homeHeroUrl, '/assets/images/3x/home-hero.jpg', '图片加载失败必须回退静态默认图');
assert.equal(failedHero.data.homeHeroAlt, '金秋入茶限定上新', '图片加载失败必须回退默认说明');

const wxml = fs.readFileSync(`${pageBase}.wxml`, 'utf8');
assert.ok(wxml.includes('src="{{homeHeroUrl}}"'), '首页主视觉必须绑定 homeHeroUrl');
assert.ok(wxml.includes('aria-label="{{homeHeroAlt}}"'), '首页主视觉必须绑定 homeHeroAlt');
assert.ok(wxml.includes('mode="aspectFill"'), '动态主视觉必须使用 aspectFill 固定比例裁切');
assert.ok(wxml.includes('binderror="handleHeroError"'), '首页主视觉必须绑定加载失败处理');

console.log('home-hero-config.test.mjs: passed');
