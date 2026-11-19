import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mall = require(path.join(root, 'packageMall/utils/mall.js'));
const detail = mall.normalizeDetail({
  id: 9,
  name: '测试商品',
  coverImage: '/api/v1/files/public/cover.jpg',
  galleryImages: [],
  detailImages: ['/api/v1/files/public/detail-one.jpg', ' ', '/api/v1/files/public/detail-two.jpg'],
  skus: []
});

assert.deepEqual(
  detail.detailImages,
  [
    'https://api.wulingshiguang.top/api/v1/files/public/detail-one.jpg?variant=detail',
    'https://api.wulingshiguang.top/api/v1/files/public/detail-two.jpg?variant=detail'
  ],
  '商品详情长图必须按接口顺序补全 URL 并过滤空地址'
);

assert.deepEqual(
  mall.removeFailedDetailImage(['/one.jpg', '/two.jpg', '/three.jpg'], 1),
  ['/one.jpg', '/three.jpg'],
  '单张详情长图失败时只能移除对应图片'
);
assert.deepEqual(mall.removeFailedDetailImage(['/only.jpg'], 0), [], '全部详情长图失败时必须隐藏图文详情区域');

const detailDir = path.join(root, 'packageMall/pages/product-detail');
const wxml = fs.readFileSync(path.join(detailDir, 'product-detail.wxml'), 'utf8');
const wxss = fs.readFileSync(path.join(detailDir, 'product-detail.wxss'), 'utf8');
const js = fs.readFileSync(path.join(detailDir, 'product-detail.js'), 'utf8');

assert.match(wxml, /wx:if="\{\{product\.detailImages\.length\}\}"/, '没有详情长图时不得渲染图文详情');
assert.match(wxml, /wx:for="\{\{product\.detailImages\}\}"/, '详情长图必须按数组顺序逐张渲染');
assert.match(
  wxml,
  /class="mall-card detail-description"[\s\S]*class="mall-card detail-images"[\s\S]*class="detail-note"/,
  '图文详情必须位于商品说明之后、底部说明之前'
);
assert.match(wxml, /mode="widthFix"/, '详情长图必须保持原始纵横比');
assert.match(wxml, /lazy-load/, '详情长图必须开启懒加载');
assert.match(wxml, /bindtap="openDetailImage"/, '详情长图必须支持点击预览');
assert.match(wxml, /data-index="\{\{index\}\}"/, '详情长图失败与预览必须能定位当前图片');
assert.match(wxml, /binderror="handleDetailImageError"/, '详情长图必须处理加载失败');
assert.match(wxml, /aria-label="商品详情图\{\{index \+ 1\}\}"/, '详情长图必须有可读的图片说明');
assert.match(wxss, /\.detail-images\s*\{/, '必须定义图文详情卡片样式');
assert.match(wxss, /\.detail-images__item\s*\{[\s\S]*?width:\s*100%/, '详情长图必须铺满内容宽度');
assert.match(js, /handleDetailImageError\(/, '页面必须实现详情长图失败降级');
assert.match(js, /removeFailedDetailImage\(/, '页面必须复用详情长图失败移除逻辑');
assert.match(js, /previewImage\(/, '页面必须调用微信图片预览');

console.log('mall-detail-images.test.mjs: passed');
