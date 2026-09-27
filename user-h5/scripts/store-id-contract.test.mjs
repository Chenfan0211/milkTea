import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 门店主键口径回归。
 *
 * 背景（真实故障）：后端 GET /api/v1/app/stores 返回 id=101(Long)、code="ST-1001"。
 * 前端内部标识沿用 code（收藏/路由/选店记忆依赖它），但下单时
 * storeSubjectId 必须是数字主键，否则后端 Long 反序列化失败 -> 500。
 */

const store = require(path.join(root, 'utils/store.js'));

const remote = {
  id: 101,
  code: 'ST-1001',
  name: '星沙乐运魔方店',
  city: '长沙市',
  address: '湖南省长沙市长沙县星沙街道开元东路288号',
  latitude: 28.2,
  longitude: 113.0,
  storeType: 'STORE',
  businessStatus: 'active'
};

const normalized = store.normalizeRemoteStore(remote);

// 前端内部标识保持 code，兼容既有收藏/路由/记忆逻辑
assert.equal(normalized.id, 'ST-1001', '前端内部 id 继续沿用业务 code');
assert.equal(normalized.code, 'ST-1001', '业务编码必须保留');

// 下单需要的数字主键单独暴露
assert.equal(normalized.subjectId, 101, 'subjectId 必须是后端数字主键');
assert.equal(typeof normalized.subjectId, 'number', 'subjectId 必须是数值类型');

console.log('门店主键口径（id=code 内部标识，subjectId 为数字主键）测试通过');
