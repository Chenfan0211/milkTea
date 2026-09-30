import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 下单 payload 的 storeSubjectId 必须是数字主键。
 *
 * 回归背景：曾把门店业务 code（ST-1001）当作 storeSubjectId 提交，
 * 后端 CreateOrderRequest.storeSubjectId 为 Long，反序列化失败返回 500。
 */

const pageJs = fs.readFileSync(path.join(root, 'pages/order-confirm/order-confirm.js'), 'utf8');
assert.ok(pageJs.includes('resolveStoreSubjectId'), '下单页必须通过 resolveStoreSubjectId 取数字主键');
assert.ok(
  /storeSubjectId:\s*this\.resolveStoreSubjectId\(\)/.test(pageJs),
  'buildOrderPayload 的 storeSubjectId 必须来自 resolveStoreSubjectId'
);
assert.ok(!/storeSubjectId:\s*storeId\b/.test(pageJs), '不得直接把前端门店 id（code）当作 storeSubjectId');

// 行为验证：resolveStoreSubjectId 返回数字主键
globalThis.getApp = () => ({ globalData: {} });
globalThis.wx = {
  showToast() {},
  showModal() {},
  navigateTo() {},
  navigateBack() {},
  showShareMenu() {}
};

let pageDefinition;
globalThis.Page = definition => {
  pageDefinition = definition;
};
require(path.join(root, 'pages/order-confirm/order-confirm.js'));

const ctx = {
  data: { store: { id: 'ST-1001', subjectId: 101 } }
};
assert.equal(pageDefinition.resolveStoreSubjectId.call(ctx), 101, '有 subjectId 时必须返回数字主键');

// 仅有 code、无 subjectId 时，不得回退成 code 字符串
const ctxNoSubject = {
  data: { store: { id: 'ST-9999', code: 'ST-9999' } }
};
const fallback = pageDefinition.resolveStoreSubjectId.call(ctxNoSubject);
assert.ok(fallback === null || typeof fallback === 'number', '查不到数字主键时不得返回字符串 code（避免后端 500）');

console.log('下单 storeSubjectId 数字主键测试通过');
