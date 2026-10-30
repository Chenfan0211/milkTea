import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));
const projectRoot = resolve(testDir, '../../..');
const source = readFileSync(resolve(projectRoot, 'src/service/api/product.ts'), 'utf8');

test('mall product split rules use the registered admin CRUD resource', () => {
  assert.match(source, /crudPage<Api\.Admin\.SplitRule>\('splitRules'/);
  assert.doesNotMatch(
    source,
    /\/api\/v1\/admin\/product\/split-rules/,
    '商品服务没有该路由，继续调用会使商品页加载表单选项时弹出服务器内部错误'
  );
});
