import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');

const cartBarJs = read('components/cart-bar/cart-bar.js');
const cartBarWxml = read('components/cart-bar/cart-bar.wxml');
const cartBarWxss = read('components/cart-bar/cart-bar.wxss');
const cartSheetJs = read('components/cart-sheet/cart-sheet.js');
const cartSheetWxml = read('components/cart-sheet/cart-sheet.wxml');
const cartSheetWxss = read('components/cart-sheet/cart-sheet.wxss');
const productWxml = read('pages/product-detail/product-detail.wxml');
const reportWxml = read('packageHealth/pages/report/report.wxml');
const reportJs = read('packageHealth/pages/report/report.js');

assert.ok(cartBarJs.includes("placement: { type: String, value: 'tabbar' }"), 'cart-bar 必须支持 placement 属性');
assert.ok(
  cartBarWxml.includes('is-safe') && cartBarWxml.includes('is-action-bar'),
  'cart-bar 必须渲染 safe/actionBar 定位类'
);
assert.ok(cartBarWxss.includes('.is-safe') && cartBarWxss.includes('.is-action-bar'), 'cart-bar 必须提供三种定位样式');
assert.ok(cartSheetJs.includes("placement: { type: String, value: 'tabbar' }"), 'cart-sheet 必须支持 placement 属性');
assert.ok(
  cartSheetWxml.includes('is-floating') && cartSheetWxml.includes('is-action-bar'),
  'cart-sheet 必须渲染 floating/actionBar 定位类'
);
assert.ok(
  cartSheetWxss.includes('.cart-sheet__panel.is-floating') &&
    cartSheetWxss.includes('.cart-sheet__panel.is-action-bar'),
  'cart-sheet 必须提供三种定位样式'
);
assert.ok(
  /\.cart-sheet__list\s*\{[\s\S]*?height:\s*510rpx/.test(cartSheetWxss),
  '购物车列表高度必须至少展示两条完整商品'
);
assert.ok(
  /\.cart-sheet__mask\s*\{[\s\S]*?background:\s*rgba\(0,\s*0,\s*0,\s*0\.46\)/.test(cartSheetWxss),
  '购物车弹层必须使用点单页半透明遮罩'
);
assert.ok(
  !productWxml.includes('<cart-bar') && !productWxml.includes('<cart-sheet'),
  '商品详情页不得展示悬浮购物车和购物车弹层'
);
assert.ok(
  reportWxml.includes('<cart-bar') && reportWxml.includes('placement="safe"'),
  '报告页悬浮层必须位于底部安全区上方'
);
assert.ok(
  reportWxml.includes('<cart-sheet') && reportWxml.includes('placement="floating"'),
  '报告页购物车面板必须位于悬浮购物车栏上方'
);
assert.ok(reportJs.includes('recommendation-cart-state'), '报告页必须接入推荐购物车状态');
assert.ok(
  reportJs.includes('syncRecommendationCart') && reportJs.includes('handleCartCheckout'),
  '报告页必须支持同步、查看和结算推荐购物车'
);
assert.ok(
  reportJs.includes('getItems(this.reportId)') && reportJs.includes('consumeSettledItems(this.reportId)'),
  '报告页推荐购物车读写必须按 reportId 隔离'
);
assert.ok(reportJs.includes('reportId: this.reportId'), '报告页结算交接必须携带 reportId');
assert.ok(reportJs.includes('reportId=') && reportJs.includes('this.reportId'), '进入商品详情必须携带 reportId');

console.log('recommendation-cart-surfaces.test.mjs: passed');
