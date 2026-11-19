import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mall = require(path.join(root, 'packageMall/utils/mall.js'));
const homeDir = path.join(root, 'packageMall/pages/home');
const detailDir = path.join(root, 'packageMall/pages/product-detail');
const homeWxml = fs.readFileSync(path.join(homeDir, 'home.wxml'), 'utf8');
const homeJs = fs.readFileSync(path.join(homeDir, 'home.js'), 'utf8');
const detailWxml = fs.readFileSync(path.join(detailDir, 'product-detail.wxml'), 'utf8');

const publicImage = '/api/v1/files/public/0123456789abcdef0123456789abcdef.jpg';
const expectedVariant = variant => `https://api.wulingshiguang.top${publicImage}?variant=${variant}`;

assert.equal(mall.imageUrl(publicImage, 'thumb'), expectedVariant('thumb'));
assert.equal(mall.imageUrl(`${publicImage}?download=1`, 'thumb'), `${expectedVariant('thumb')}&download=1`);
assert.equal(
  mall.imageUrl('https://cdn.example.com/product.jpg', 'display'),
  'https://cdn.example.com/product.jpg',
  '外部完整地址不得追加文件服务规格参数'
);
assert.equal(
  mall.imageUrl('/assets/images/3x/menu-product.jpg', 'thumb'),
  '/assets/images/3x/menu-product.jpg',
  '本地素材不得追加文件服务规格参数'
);

const product = mall.normalizeProduct({
  id: 1,
  name: '性能优化商品',
  coverImage: publicImage,
  galleryImages: [publicImage],
  availableStock: 2
});
assert.equal(product.image, expectedVariant('thumb'), '商品列表封面必须使用 thumb 规格');
assert.deepEqual(product.galleryImages, [expectedVariant('thumb')], '商品列表轮播必须使用 thumb 规格');

const detail = mall.normalizeDetail({
  id: 1,
  name: '性能优化商品',
  coverImage: publicImage,
  galleryImages: [publicImage],
  detailImages: [publicImage],
  skus: []
});
assert.deepEqual(detail.galleryImages, [expectedVariant('display')], '详情主图必须使用 display 规格');
assert.deepEqual(detail.detailImages, [expectedVariant('detail')], '详情长图必须使用 detail 规格');

assert.match(
  homeWxml,
  /wx:if="\{\{visibleGalleryIds\[item\.id\] && item\.galleryImages\.length > 1\}\}"[\s\S]*?class="product-card__swiper"/,
  '只有进入视口的多图商品卡才渲染完整轮播'
);
assert.match(
  homeWxml,
  /wx:else[\s\S]*class="product-card__image"[\s\S]*src="\{\{item\.image\}\}"/,
  '离屏商品卡只能加载封面图'
);
assert.match(homeJs, /visibleGalleryIds:\s*\{\}/, '首页必须继续维护可见商品集合');
assert.match(
  homeJs,
  /const visible = Number\(entry\.intersectionRatio\) > 0;[\s\S]*visibleGalleryIds:/,
  '商品卡可见性变化必须立即驱动图片渲染'
);

assert.match(
  detailWxml,
  /<swiper-item wx:for="\{\{galleryImages\}\}"[\s\S]*?src="\{\{item\}\}"[\s\S]*?lazy-load/,
  '详情主图必须继续使用按需加载'
);

console.log('mall-image-performance.test.mjs: passed');
