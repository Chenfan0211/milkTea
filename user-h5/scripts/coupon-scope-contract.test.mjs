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
