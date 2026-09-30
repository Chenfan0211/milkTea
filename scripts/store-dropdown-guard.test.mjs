/**
 * 门店下拉数据源守卫（CI 检查）
 *
 * 背景（2026-09-28 线上现象）：
 *   运营后台「编辑门店」弹窗里，门店类型 / 城市下拉展开后空白。
 *   1) 门店类型数据源是 sys_dict_item，字段为 item_code/item_name，
 *      而页面用了不存在的 t.name -> label 全为 undefined；
 *   2) 城市数据源 region 有 name 字段（用法正确），但页面 remoteDeps
 *      未声明 'cities'，AdminListPage 从不预加载 -> store.cities 恒为空。
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
let failed = 0;
function check(ok, msg) {
  if (ok) console.log(`  ✓ ${msg}`);
  else {
    console.error(`  ✗ ${msg}`);
    failed += 1;
  }
}

for (const rel of ['src/views/subject/store/index.vue', 'src/views/subject/channel/index.vue']) {
  console.log(`\n检查 ${rel}`);
  const s = readFileSync(join(root, rel), 'utf8');

  console.log('  1) 门店类型字段名');
  check(!/storeTypes[\s\S]{0,200}label:\s*t\.name/.test(s), '不得再使用 t.name（sys_dict_item 无该字段）');
  check(/label:\s*t\.itemName/.test(s), '必须使用 t.itemName 作为门店类型展示名');
  console.log('  2) 只取门店类型字典');
  check(/dictType\s*===\s*'store_type'/.test(s), '必须按 dictType === store_type 过滤（sys_dict_item 混存所有字典）');
}

console.log('\n检查 门店页 remoteDeps');
const storePage = readFileSync(join(root, 'src/views/subject/store/index.vue'), 'utf8');
check(/remoteDeps:\s*\[[^\]]*'cities'/.test(storePage), 'store 页 remoteDeps 必须包含 cities');

if (failed > 0) {
  console.error(`\n失败 ${failed} 项`);
  process.exit(1);
}
console.log('\n门店下拉数据源守卫检查通过');
