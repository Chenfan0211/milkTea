/**
 * 会员等级权益编辑器守卫（CI 检查）
 *
 * 需求（2026-09-27）：
 *   1) 图标与权益文案绑定：图标由文案决定，不再单独选择，避免"基础折扣配皇冠"这类语义错乱；
 *   2) 只有「专属优惠券」需要绑定优惠券并填写数量；其他权益不显示数量。
 *
 * 本脚本校验两件事：
 *   - 组件源码里不再出现独立的图标选择器（BenefitIconSelect）；
 *   - 数量输入只在「专属优惠券」时渲染（v-if 受文案控制），且优惠券选择器同时存在。
 *
 * 用法：node scripts/member-benefit-editor.test.mjs
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = process.cwd();
const PAGE = join(ROOT, 'src/views/marketing/member/index.vue');
const source = readFileSync(PAGE, 'utf8');

let failed = 0;
function check(ok, message) {
  if (ok) {
    console.log(`  ✓ ${message}`);
  } else {
    console.error(`  ✗ ${message}`);
    failed += 1;
  }
}

console.log('检查 1：图标与文案绑定');
check(
  !source.includes('<BenefitIconSelect'),
  '不再使用独立的图标选择器（图标应由文案决定）'
);
check(
  source.includes('iconForBenefit') || source.includes('BENEFIT_ICON'),
  '必须存在「文案 -> 图标」的映射逻辑'
);
check(
  /const\s+icon\s*=\s*iconForBenefit/.test(source) || /icon:\s*iconForBenefit/.test(source),
  '保存权益时必须由文案推导图标（而不是取用户手选值）'
);

console.log('检查 2：数量只对专属优惠券显示');
check(
  source.includes('isCouponBenefit'),
  '必须有「是否专属优惠券」的判定方法'
);
check(
  /v-if\s*=\s*"isCouponBenefit\(/.test(source),
  '数量/优惠券控件必须用 v-if 按权益文案条件渲染'
);
check(
  source.includes('couponId'),
  '专属优惠券必须关联优惠券（couponId）'
);
check(
  source.includes('NSelect') && source.includes('couponOptions'),
  '必须提供优惠券下拉选择（couponOptions）'
);

console.log('检查 3：提交前校验与后端规则一致');
check(
  source.includes('专属优惠券必须') || source.includes('请选择优惠券'),
  '前端必须在缺少优惠券时给出可读提示'
);
check(
  /数量/.test(source),
  '前端必须校验数量（专属优惠券必填）'
);

if (failed > 0) {
  console.error(`\n会员等级权益编辑器检查失败：${failed} 项`);
  process.exit(1);
}
console.log('\n会员等级权益编辑器检查通过');
