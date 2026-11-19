import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');
const wxml = read('packageMall/pages/home/home.wxml');
const wxss = read('packageMall/pages/home/home.wxss');
const js = read('packageMall/pages/home/home.js');
const api = read('utils/api.js');

assert.match(api, /function fetchMallConfig\(\)/, '小程序 API 必须提供商城配置读取函数');
assert.match(api, /url:\s*['"]\/api\/v1\/app\/config\/mall['"]/, '商城配置必须读取独立 mall 接口');
assert.match(js, /require\(['"]\.\.\/\.\.\/\.\.\/utils\/mall-banner['"]\)/, '商城首页必须复用轮播归一化工具');
assert.match(js, /mallBanners:\s*\[\]/, '商城轮播首屏默认必须为空，未配置时不显示');
assert.match(js, /loadBanners\(\)/, '商城首页必须加载轮播配置');
assert.match(js, /api\s*\.fetchMallConfig\(\)/, '商城首页必须调用独立配置接口');
assert.match(js, /openMallBanner\(/, '商城首页必须提供轮播点击处理');
assert.match(js, /handleMallBannerError\(/, '商城首页必须处理轮播图片加载失败');

assert.match(
  wxml,
  /class="home-fixed"[\s\S]*?class="home-hero"[\s\S]*?class="home-banner-swiper"[\s\S]*?class="category-scroll"/,
  '商城轮播必须位于标题区和分类栏之间'
);
assert.match(wxml, /wx:if="\{\{mallBanners\.length\}\}"/, '没有轮播配置时不得渲染轮播');
assert.match(wxml, /wx:for="\{\{mallBanners\}\}"/, '商城轮播必须逐图渲染');
assert.match(wxml, /autoplay="\{\{mallBanners\.length > 1\}\}"/, '多张轮播必须自动播放');
assert.match(wxml, /interval="3000"/, '轮播间隔必须为 3 秒');
assert.match(wxml, /circular="\{\{mallBanners\.length > 1\}\}"/, '多张轮播必须循环');
assert.match(wxml, /indicator-dots="\{\{mallBanners\.length > 1\}\}"/, '多张轮播必须显示指示点');
assert.match(wxml, /mode="aspectFill"/, '商城轮播必须固定比例裁切');
assert.match(wxml, /binderror="handleMallBannerError"/, '轮播图片失败必须触发降级');
assert.match(wxml, /item\.targetType !== 'none'/, '无跳转轮播不得渲染点击入口');
assert.match(wxml, /bindtap="openMallBanner"/, '有跳转轮播必须绑定点击');

assert.match(
  wxss,
  /\.home-banner-swiper\s*\{[\s\S]*?width:\s*710rpx;[\s\S]*?height:\s*260rpx;[\s\S]*?border-radius:\s*var\(--radius-lg\)/,
  '商城轮播必须使用 710×260rpx 和设计系统大圆角'
);
assert.match(wxss, /\.home-banner\s*\{[\s\S]*?width:\s*710rpx;[\s\S]*?height:\s*260rpx/, '商城轮播图片尺寸必须固定');
assert.doesNotMatch(wxss, /#[0-9A-Fa-f]{3,8}\b/, '商城首页轮播不得新增颜色字面量');

console.log('mall-home-banner.test.mjs: passed');
