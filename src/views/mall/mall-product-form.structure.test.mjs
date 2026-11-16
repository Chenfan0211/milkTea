import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDir, '../../..');
const source = readFileSync(resolve(projectRoot, 'src/views/mall/product/index.vue'), 'utf8');
const api = readFileSync(resolve(projectRoot, 'src/service/api/crud.ts'), 'utf8');
const upload = readFileSync(resolve(projectRoot, 'src/views/mall/product/ProductImageUpload.vue'), 'utf8');

test('mall product form keeps the supplier only at SPU level', () => {
  assert.match(source, /fetchSubjectsByType\('SUPPLIER'\)/, 'SPU 供应商必须使用主体下拉');
  assert.doesNotMatch(source, /fetchSubjectsByType\('STORE'\)/, '商品不再维护归属门店');
  assert.doesNotMatch(source, /fetchSubjectsByType\('CHANNEL'\)/, '商品不再维护归属渠道');
  assert.doesNotMatch(source, /fetchAdminSplitRules/, '商品不再选择分账规则');
  assert.doesNotMatch(source, /attributionStoreSubjectId/, '商品表单不得保留归属门店字段');
  assert.doesNotMatch(source, /attributionChannelSubjectId/, '商品表单不得保留归属渠道字段');
  assert.doesNotMatch(source, /splitRuleId/, '商品表单不得保留分账规则字段');
  assert.doesNotMatch(source, /sku\.supplierSubjectId/, 'SKU 不再单独绑定供应商');
});

test('mall product images use the file service instead of address inputs', () => {
  assert.match(source, /ProductImageUpload/, '商品主图和 SKU 图片必须复用上传组件');
  assert.match(upload, /uploadAdminImage/, '图片必须调用系统真实上传接口');
  assert.match(upload, /5 \* 1024 \* 1024/, '图片上传必须限制 5MB');
  assert.doesNotMatch(source, /主图地址/, '主图不得展示图片地址');
  assert.doesNotMatch(source, /图片地址/, 'SKU 图片不得展示图片地址');
});

test('mall product form shows all SKU prices in yuan and hides sorting', () => {
  for (const label of ['售价（元）', '划线价（元）', '成本价（元）', '平台提成（元）', '分销佣金（元）']) {
    assert.match(source, new RegExp(label), `SKU 必须展示 ${label}`);
  }
  assert.doesNotMatch(source, /售价\(分\)|划线价\(分\)|成本价\(分\)|平台提成\(分\)|分销佣金\(分\)/);
  assert.doesNotMatch(source, /label="排序"/, '商品和 SKU 不再展示排序号');
  assert.doesNotMatch(source, /title: '排序'/, '列表不再展示排序号');
});

test('subject binding list api is reused for dropdown data', () => {
  assert.match(api, /\/api\/v1\/admin\/subject\/binding\/list\/\$\{type\}/);
});
