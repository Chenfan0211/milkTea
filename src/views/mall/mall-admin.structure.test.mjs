import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDir, '../../..');

function pathOf(relativePath) {
  return resolve(projectRoot, relativePath);
}

function readIfPresent(relativePath, label) {
  const absolutePath = pathOf(relativePath);
  assert.equal(existsSync(absolutePath), true, `${label} should exist at ${relativePath}`);
  return readFileSync(absolutePath, 'utf8');
}

test('mall admin page files are present and use the shared list shell', () => {
  const pages = [
    'src/views/mall/category/index.vue',
    'src/views/mall/product/index.vue',
    'src/views/mall/order/index.vue',
    'src/views/mall/after-sale/index.vue'
  ];

  for (const page of pages) {
    const source = readIfPresent(page, page);
    assert.match(source, /AdminListPage/, `${page} should use AdminListPage`);
  }
});

test('mall api uses the confirmed product status and stock contracts', () => {
  const source = readIfPresent('src/service/api/mall.ts', 'mall api');

  assert.match(source, /\/api\/v1\/admin\/mall\/products\/\$\{id\}\/status/);
  assert.match(source, /method:\s*['"]patch['"]/);
  assert.match(source, /data:\s*\{\s*status\s*\}/);
  assert.match(source, /\/api\/v1\/admin\/mall\/products\/\$\{id\}\/skus\/\$\{skuId\}\/stock/);
  assert.match(source, /data:\s*\{\s*stock\s*,\s*remark\s*\}/);
  assert.match(source, /\/api\/v1\/admin\/trade\/orders\/\$\{orderNo\}\/ship/);
});

test('mall product page exposes the required drawer and SKU maintenance fields', () => {
  const source = readIfPresent('src/views/mall/product/index.vue', 'mall product page');
  const importsMallApi = /@\/service\/api\/mall/.test(source);

  assert.equal(importsMallApi, true, 'product page requests must go through the mall API module');
  assert.match(source, /NDrawer/);
  assert.match(source, /categoryId/);
  assert.match(source, /productCode/);
  assert.match(source, /subtitle/);
  assert.match(source, /specName/);
  assert.match(source, /coverImage/);
  assert.match(source, /description/);
  assert.match(source, /attributionStoreSubjectId/);
  assert.match(source, /attributionChannelSubjectId/);
  assert.match(source, /splitRuleId/);
  assert.match(source, /specValue/);
  assert.match(source, /salePrice/);
  assert.match(source, /originalPrice/);
  assert.match(source, /costPrice/);
  assert.match(source, /platformCommission/);
  assert.match(source, /supplierSubjectId/);
  assert.match(source, /deletable/, '商品页必须按后端 deletable 控制删除');
  assert.match(source, /deleteBlockedReason/, '商品页必须展示不可删除原因');
  assert.match(source, /stockAvailableAfter/, '库存弹窗必须展示调整后可用库存');
  assert.match(source, /库存调整/);
});

test('mall order page uses the shared detail modal and shipping fields', () => {
  const source = readIfPresent('src/views/mall/order/index.vue', 'mall order page');

  assert.match(source, /DetailModal/);
  assert.match(source, /courierCompany/);
  assert.match(source, /trackingNo/);
  assert.match(source, /orderType:\s*['"]MALL['"]/);
  assert.match(source, /shipping/);
});

test('mall after-sale page supports review and refund state display', () => {
  const source = readIfPresent('src/views/mall/after-sale/index.vue', 'mall after-sale page');
  const api = readIfPresent('src/service/api/mall.ts', 'mall api');

  assert.match(source, /fetchMallAfterSales/);
  assert.match(source, /approveMallAfterSale/);
  assert.match(source, /rejectMallAfterSale/);
  assert.match(source, /DetailModal/);
  assert.match(source, /REFUNDED/);
  assert.match(source, /PENDING/);
  assert.match(source, /REFUNDING/);
  assert.match(source, /FAILED/);
  assert.match(source, /refundNo/);
  assert.match(api, /\/api\/v1\/admin\/trade\/after-sales/);
  assert.match(api, /\/approve/);
  assert.match(api, /\/reject/);
});
