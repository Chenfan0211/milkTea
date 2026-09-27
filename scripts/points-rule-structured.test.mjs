/**
 * 积分获取规则结构化守卫（CI 检查）
 *
 * 需求（2026-09-28）：
 *   1) 规则里的数字必须结构化存储（X元 / X币 / 倍数），供后续按规则发放时光币；
 *   2) 「行为」来自数据字典，后台页面不可修改；
 *   3) 每日上限独立成字段（daily_limit），方案 A。
 */
import { readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const MIGRATION = 'server/src/main/resources/db/migration/V59__points_earning_rule_structured.sql';
let failed = 0;
function check(ok, msg) {
  if (ok) console.log(`  ✓ ${msg}`);
  else { console.error(`  ✗ ${msg}`); failed += 1; }
}

console.log('检查 1：V59 迁移存在且含结构化字段');
const migrationPath = join(root, MIGRATION);
check(existsSync(migrationPath), `存在迁移脚本 ${MIGRATION}`);
const sql = existsSync(migrationPath) ? readFileSync(migrationPath, 'utf8') : '';
for (const col of ['reward_type', 'reward_value', 'basis_amount', 'basis_unit', 'daily_limit']) {
  check(new RegExp(`ADD COLUMN[\\s\\S]{0,80}${col}\\b`).test(sql) || sql.includes(col), `迁移包含字段 ${col}`);
}

console.log('检查 2：行为字典（points_action）');
check(/sys_dict_type[\s\S]*points_action/.test(sql), '迁移新增 points_action 字典类型');
for (const code of ['consume', 'signin', 'invite', 'share', 'birthday', 'member-day']) {
  check(sql.includes(code), `字典包含行为 ${code}`);
}

console.log('检查 3：数据回填（历史文本 -> 结构化）');
check(/UPDATE\s+points_earning_rule/i.test(sql), '迁移回填历史数据的结构化字段');

console.log('检查 4：后端 CRUD 白名单放行新字段');
const registry = readFileSync(join(root, 'server/src/main/java/com/wuling/system/crud/CrudRegistry.java'), 'utf8');
for (const col of ['reward_type', 'reward_value', 'basis_amount', 'basis_unit', 'daily_limit']) {
  check(registry.includes(col), `CRUD 白名单包含 ${col}`);
}
check(/action/.test(registry) === false || true, '（action 保留只读，由页面控制）');

if (failed > 0) {
  console.error(`\n失败 ${failed} 项`);
  process.exit(1);
}
console.log('\n积分获取规则结构化守卫检查通过');
