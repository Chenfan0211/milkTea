import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const banner = require(path.join(root, 'utils/profile-banner.js'));
const pageBase = path.join(root, 'pages/profile/profile');

const resolveAssetUrl = value => {
  const raw = String(value || '').trim();
  return raw.indexOf('/api/') === 0 ? 'https://api.example.com' + raw : raw;
};

const remote = banner.normalizeBanners(
  [
    { id: 2, imageUrl: '/api/v1/files/public/two.jpg', altText: '第二张' },
    { id: 3, imageUrl: '', altText: '空图' },
    { id: 1, imageUrl: '/assets/images/3x/profile-banner.jpg', altText: '' }
  ],
  resolveAssetUrl
);
assert.deepEqual(
  remote,
  [
    { id: '2', imageUrl: 'https://api.example.com/api/v1/files/public/two.jpg', altText: '第二张' },
    { id: '1', imageUrl: '/assets/images/3x/profile-banner.jpg', altText: '抹茶轻茉莉新品' }
  ],
  '轮播必须过滤空图、保留后端顺序并补全相对 API 域名'
);

assert.deepEqual(banner.normalizeBanners([], resolveAssetUrl), [], '后台返回空数组时必须允许隐藏轮播');
assert.deepEqual(
  banner.normalizeBanners(null, resolveAssetUrl),
  banner.DEFAULT_PROFILE_BANNERS,
  '无效配置必须回退默认轮播'
);

const removed = banner.removeFailedBanner(
  [
    { id: 'a', imageUrl: '/a.jpg', altText: 'A' },
    { id: 'b', imageUrl: '/b.jpg', altText: 'B' }
  ],
  0
);
assert.deepEqual(
  removed.map(item => item.id),
  ['b'],
  '单张加载失败时必须只移除该张'
);

assert.deepEqual(
  banner.removeFailedBanner([{ id: 'only', imageUrl: '/only.jpg', altText: 'Only' }], 0),
  banner.DEFAULT_PROFILE_BANNERS,
  '轮播全部失效时必须回退默认图'
);

const wxml = fs.readFileSync(`${pageBase}.wxml`, 'utf8');
assert.ok(wxml.includes('<swiper'), '我的页轮播必须使用 swiper');
assert.ok(wxml.includes('autoplay="{{profileBanners.length > 1}}"'), '多张轮播必须自动播放');
assert.ok(wxml.includes('interval="3000"'), '轮播间隔必须为 3 秒');
assert.ok(wxml.includes('circular="{{profileBanners.length > 1}}"'), '多张轮播必须循环');
assert.ok(wxml.includes('indicator-dots="{{profileBanners.length > 1}}"'), '多张轮播必须显示指示点');
assert.ok(wxml.includes('mode="aspectFill"'), '轮播图片必须固定比例裁切');
assert.ok(wxml.includes('bindtap="openMenu"'), '所有轮播图必须统一点击进入点单页');

const wxss = fs.readFileSync(`${pageBase}.wxss`, 'utf8');
assert.ok(/\.promo-swiper\s*\{[\s\S]*width:\s*710rpx[\s\S]*height:\s*160rpx/.test(wxss), '轮播必须固定为 710×160rpx');
assert.ok(/\.promo-banner\s*\{[\s\S]*height:\s*160rpx/.test(wxss), '轮播图片必须固定 160rpx 高度');

console.log('profile-banner-carousel.test.mjs: passed');
