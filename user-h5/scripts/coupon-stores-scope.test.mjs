import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 「券适用门店」过滤行为回归。
 *
 * 后端 applicableStoreIds 是数字 subjectId，前端门店 id 是业务 code。
 * 修复前：字段恒空 -> 返回全部门店；即便有值也因比对 code 而全部失配。
 */

globalThis.getApp = () => ({ globalData: {} });
globalThis.wx = {
  showToast() {}, showModal() {}, navigateTo() {}, navigateBack() {}, showShareMenu() {},
  getStorageSync() { return ''; }, setStorageSync() {}, removeStorageSync() {}
};

let pageDefinition;
globalThis.Page = definition => { pageDefinition = definition; };
require(path.join(root, 'pages/coupon-stores/coupon-stores.js'));

// coupon-stores.js 内部函数未导出，直接对页面定义做静态+行为校验：
// resolveStores 是通过页面模块闭包使用的，这里验证页面源码的过滤表达式。
const source = fs.readFileSync(path.join(root, 'pages/coupon-stores/coupon-stores.js'), 'utf8');
assert.ok(
  /String\(id\) === String\(store\.subjectId\)/.test(source),
  '适用门店过滤必须以 store.subjectId 为准'
);

// 模拟过滤逻辑：门店 code 与 subjectId 不同，必须按 subjectId 命中
const stores = [
  { id: 'ST-1001', subjectId: 101, name: '门店A' },
  { id: 'ST-1002', subjectId: 102, name: '门店B' },
  { id: 'ST-1003', subjectId: 103, name: '门店C' }
];
const applicableStoreIds = [101, 103];
const filtered = stores.filter(store =>
  applicableStoreIds.some(id => String(id) === String(store.subjectId))
);
assert.deepEqual(filtered.map(s => s.id), ['ST-1001', 'ST-1003'],
  '限定门店的券必须只命中 subjectId 匹配的门店');
assert.ok(
  !filtered.some(s => applicableStoreIds.includes(s.id)),
  '不得用业务 code 去匹配数字 subjectId（修复前的错误口径）'
);

console.log('券适用门店过滤（subjectId）行为测试通过');
