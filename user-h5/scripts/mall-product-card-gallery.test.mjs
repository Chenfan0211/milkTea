import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mall = require(path.join(root, 'packageMall/utils/mall.js'));
const homeDir = path.join(root, 'packageMall/pages/home');
const detailDir = path.join(root, 'packageMall/pages/product-detail');
const homeWxml = fs.readFileSync(path.join(homeDir, 'home.wxml'), 'utf8');
const homeWxss = fs.readFileSync(path.join(homeDir, 'home.wxss'), 'utf8');
const homeJs = fs.readFileSync(path.join(homeDir, 'home.js'), 'utf8');
const detailWxml = fs.readFileSync(path.join(detailDir, 'product-detail.wxml'), 'utf8');

const card = mall.normalizeProduct({
  id: 9,
  name: '多图商品',
  coverImage: '/assets/images/3x/menu-product.jpg',
  galleryImages: ['/assets/images/3x/tea-dahongpao.jpg', '/assets/images/3x/tea-yunling-yimi.jpg'],
  availableStock: 5
});
assert.deepEqual(card.galleryImages, ['/assets/images/3x/tea-dahongpao.jpg', '/assets/images/3x/tea-yunling-yimi.jpg']);
assert.deepEqual(
  mall.normalizeProduct({
    id: 10,
    name: '旧商品',
    coverImage: '/assets/images/3x/menu-product.jpg',
    availableStock: 1
  }).galleryImages,
  ['/assets/images/3x/menu-product.jpg'],
  '旧商品没有多图时必须用封面图组成单张轮播'
);

assert.match(homeWxml, /class="product-card__swiper"/, '商品卡主图必须使用 swiper');
assert.match(homeWxml, /wx:for="\{\{item\.galleryImages\}\}"/, '商品卡必须逐张渲染主图');
assert.match(
  homeWxml,
  /wx:if="\{\{visibleGalleryIds\[item\.id\] && item\.galleryImages\.length > 1\}\}"[\s\S]*?autoplay="\{\{true\}\}"/,
  '只有进入视口的多图商品卡才创建自动轮播'
);
assert.match(homeWxml, /interval="3000"/, '商品卡轮播间隔必须为 3 秒');
assert.match(homeWxml, /circular="\{\{true\}\}"/, '可见的多图商品卡必须循环');
assert.match(homeWxml, /bindchange="handleCardGalleryChange"/, '商品卡必须记录当前轮播页');
assert.match(homeWxml, /product-card__gallery-counter/, '多图商品卡必须显示当前页角标');
assert.match(
  homeWxml,
  /class="product-card__image-wrap"[\s\S]*?data-id="\{\{item\.id\}\}"/,
  '商品卡图片容器必须暴露商品 id 供可见性观察'
);
assert.match(homeWxss, /\.product-card__swiper\s*\{[\s\S]*?height:\s*300rpx/, '商品卡 swiper 必须固定 300rpx 高');
assert.match(homeWxss, /\.product-card__gallery-counter\s*\{/, '商品卡必须定义轮播角标样式');

assert.match(homeJs, /visibleGalleryIds:\s*\{\}/, '首页必须维护可见商品轮播集合');
assert.match(homeJs, /createIntersectionObserver\(this,\s*\{\s*observeAll:\s*true\s*\}\)/, '首页必须观察全部商品卡');
assert.match(homeJs, /relativeToViewport\(\{\s*top:\s*0,\s*bottom:\s*0\s*\}\)/, '商品卡必须按视口判断可见性');
assert.match(homeJs, /\.product-card__image-wrap/, '必须观察商品图片容器');
assert.match(homeJs, /setupProductCardObserver\(/, '首页必须封装商品卡可见性观察');
assert.match(homeJs, /onHide\(\)\s*\{[\s\S]*?disconnect/, '页面隐藏时必须停止观察轮播');
assert.match(homeJs, /onUnload\(\)\s*\{[\s\S]*?disconnect/, '页面卸载时必须断开观察器');
assert.match(homeJs, /wx\.nextTick\([^]*?setupProductCardObserver\(/, '商品加载或分页后必须重新绑定观察器');

assert.match(
  detailWxml,
  /autoplay="\{\{galleryImages\.length > 1\}\}"[\s\S]*?interval="3000"[\s\S]*?circular="\{\{galleryImages\.length > 1\}\}"/,
  '详情页多图必须每 3 秒自动循环播放'
);

let pageDefinition = null;
let observedCallback = null;
let relativeOptions = null;
let observedSelector = '';
const observer = {
  relativeToViewport(options) {
    relativeOptions = options;
    return this;
  },
  observe(selector, callback) {
    observedSelector = selector;
    observedCallback = callback;
    return this;
  },
  disconnect() {}
};
vm.runInNewContext(homeJs, {
  require: id => {
    if (id.endsWith('/api')) return {};
    if (id.endsWith('/mall')) return { categoryIconUrl: () => '/assets/images/3x/mall-category-placeholder.jpg' };
    if (id.endsWith('/mall-cart')) return {};
    if (id.endsWith('/mall-banner')) return {};
    if (id.endsWith('/share')) return { withShare: value => value };
    return {};
  },
  Page: value => {
    pageDefinition = value;
  },
  wx: {
    createIntersectionObserver: () => observer
  },
  console
});
const page = {
  data: { products: [{ id: 9 }], visibleGalleryIds: {} },
  setData(patch) {
    Object.assign(this.data, patch);
  }
};
pageDefinition.setupProductCardObserver.call(page);
assert.equal(relativeOptions.top, 0);
assert.equal(relativeOptions.bottom, 0);
assert.equal(observedSelector, '.product-card__image-wrap');
observedCallback({ dataset: { id: '9' }, intersectionRatio: 0.8 });
assert.equal(page.data.visibleGalleryIds[9], true, '商品卡进入视口后必须开启自动轮播');
observedCallback({ dataset: { id: '9' }, intersectionRatio: 0 });
assert.equal(page.data.visibleGalleryIds[9], false, '商品卡离开视口后必须暂停自动轮播');

console.log('mall-product-card-gallery.test.mjs: passed');
