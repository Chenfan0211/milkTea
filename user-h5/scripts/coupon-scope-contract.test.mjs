import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 券适用范围口径回归。
 *
 * 背景：后端 applicableStoreIds / applicableProductIds 为数字主键
 * （subjectId / productId），而前端门店 id 用的是业务 code（ST-1001）。
 * 若仍用 store.id 比对，限定门店的券永远匹配不上，会被错误放行。
 */

// 1. 下单页门店校验必须使用 subjectId
const orderConfirm = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
const scopeBlock = orderConfirm.slice(
  orderConfirm.indexOf('applicableStoreIds'),
  orderConfirm.indexOf('applicableStoreIds') + 400
);
assert.ok(
  /store\s*&&\s*store\.subjectId/.test(scopeBlock),
  '下单页券门店校验必须使用 store.subjectId（数字主键），不能用 store.id（业务 code）'
);

// 2. 券适用门店页必须按 subjectId 过滤
const couponStores = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.js'), 'utf8');
assert.ok(/store\.subjectId/.test(couponStores), '券适用门店页必须按 store.subjectId 过滤适用门店');
assert.ok(
  !/applicableStoreIds\.indexOf\(store\.id\)/.test(couponStores),
  '不得再用 store.id 与 applicableStoreIds 比对'
);

console.log('券适用范围前端口径（subjectId）测试通过');

// 3. 「选择门店」页右上角城市必须接入通用「选择城市」页（city-picker），
//    并与点单页共用同一套 selectCity / 持久化逻辑；切城市后留在本页刷新（onShow）。
assert.ok(
  /selectCity\s*\(\)\s*\{[\s\S]*?\/pages\/city-picker\/city-picker/.test(couponStores),
  '券适用门店页必须提供 selectCity 并跳转通用城市选择页 city-picker'
);
assert.ok(/onShow\s*\(\)\s*\{/.test(couponStores), '券适用门店页必须实现 onShow，从城市页返回后刷新城市与门店列表');
assert.ok(/syncStoresByCity\s*\(\)\s*\{/.test(couponStores), '城市与门店列表重算必须收敛到 syncStoresByCity 单一入口');
assert.ok(/currentCityCode/.test(couponStores), '券适用门店页必须记录 currentCityCode，用于 onShow 判断城市是否变化');

const couponStoresWxmlForCity = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.wxml'), 'utf8');
assert.ok(
  /class="store-filter__city"[^>]*bindtap="selectCity"/.test(couponStoresWxmlForCity),
  '券适用门店页右上角城市必须绑定 selectCity（不再绑定 showUnavailable）'
);
assert.ok(
  !/class="store-filter__city"[^>]*showUnavailable/.test(couponStoresWxmlForCity),
  '券适用门店页城市入口不得再走 showUnavailable 未接入提示'
);
