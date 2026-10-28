import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDir, '../../..');
const source = readFileSync(resolve(projectRoot, 'src/views/mall/product/index.vue'), 'utf8');
const api = readFileSync(resolve(projectRoot, 'src/service/api/crud.ts'), 'utf8');

test('mall product form uses subject and split-rule dropdowns instead of raw ids', () => {
  assert.match(source, /fetchSubjectsByType\('STORE'\)/, '归属门店必须使用主体下拉');
  assert.match(source, /fetchSubjectsByType\('CHANNEL'\)/, '归属渠道必须使用主体下拉');
  assert.match(source, /fetchSubjectsByType\('SUPPLIER'\)/, 'SKU 供应商必须使用主体下拉');
  assert.match(source, /fetchAdminSplitRules/, '分账规则必须使用规则下拉');
  assert.doesNotMatch(source, /label="归属门店ID"/, '不得继续要求运营手填门店 ID');
  assert.doesNotMatch(source, /label="渠道ID（可选）"/, '不得继续要求运营手填渠道 ID');
  assert.doesNotMatch(source, /label="分账规则ID（可选）"/, '不得继续要求运营手填规则 ID');
  assert.doesNotMatch(source, /label="供应商ID"/, '不得继续要求运营手填供应商 ID');
});

test('subject binding list api is reused for dropdown data', () => {
  assert.match(api, /\/api\/v1\/admin\/subject\/binding\/list\/\$\{type\}/);
});
