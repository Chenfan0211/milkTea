/**
 * 积分规则启用状态守卫（CI 检查）
 *
 * 需求：规则支持停用/启用；只有启用才对小程序端生效并展示。
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
const page = readFileSync(join(root, 'src/views/marketing/points-rule/index.vue'), 'utf8');
let failed = 0;
function check(ok, msg) {
  if (ok) console.log(`  ✓ ${msg}`);
  else {
    console.error(`  ✗ ${msg}`);
    failed += 1;
  }
}

console.log('检查 1：前端页面有启用/停用交互');
check(/toggleEnabled/.test(page), '必须存在 toggleEnabled 切换函数');
check(/启用|停用/.test(page), '必须有启用/停用文案');
check(/enabled/.test(page), '必须读写 enabled 字段');

console.log('检查 2：后端过滤');
const pointsService = readFileSync(
  join(root, 'marketing-service/src/main/java/com/wuling/marketing/service/PointsService.java'),
  'utf8'
);
check(/enabledEarningRules/.test(pointsService), '必须存在 enabledEarningRules 方法');
check(/getEnabled,\s*1/.test(pointsService), '必须按 enabled=1 过滤');

const controller = readFileSync(
  join(root, 'marketing-service/src/main/java/com/wuling/marketing/controller/AppMarketingController.java'),
  'utf8'
);
check(/enabledEarningRules\(\)/.test(controller), 'App 端接口必须调用 enabledEarningRules');

console.log('检查 3：数据库迁移');
const mig = readFileSync(
  join(root, 'server/src/main/resources/db/migration/V60__points_earning_rule_enabled.sql'),
  'utf8'
);
check(/ADD COLUMN enabled/.test(mig), 'V60 迁移必须新增 enabled 列');
check(/DEFAULT 1/.test(mig), 'enabled 默认值必须为 1（启用）');

if (failed > 0) {
  console.error(`\n失败 ${failed} 项`);
  process.exit(1);
}
console.log('\n积分规则启用状态守卫检查通过');
