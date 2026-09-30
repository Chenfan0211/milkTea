/**
 * 积分获取规则页守卫（CI 检查）
 *
 * 需求（2026-09-28）：
 *   1) 「行为」来自字典 points_action，当前页不可修改；
 *   2) 奖励拆成结构化数字（类型 + 数值 + 基准金额/单位 + 每日上限），可编辑存储；
 *   3) 本页不再新增/删除规则（行为由字典固定）。
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

console.log('检查 1：行为只读（来自字典）');
check(/points_action/.test(page), '页面必须读取 points_action 字典作为行为来源');
check(!/NInput\s+v-model:value="ruleForm\.action"/.test(page), '行为不得用可编辑输入框');
check(/行为[\s\S]{0,120}(只读|readonly|disabled|字典)/.test(page), '行为区域必须有只读/字典来源说明');

console.log('检查 2：奖励结构化字段');
for (const f of ['rewardType', 'rewardValue', 'basisAmount', 'basisUnit', 'dailyLimit']) {
  check(page.includes(f), `表单包含结构化字段 ${f}`);
}
check(/per-yuan|multiplier/.test(page), '页面必须处理 per-yuan / multiplier 类型');

console.log('检查 3：不新增/删除规则');
check(!/新增规则/.test(page), '不得再有「新增规则」按钮（行为由字典固定）');
check(!/removeRule/.test(page), '不得再有删除规则操作');

if (failed > 0) {
  console.error(`\n失败 ${failed} 项`);
  process.exit(1);
}
console.log('\n积分获取规则页守卫检查通过');
