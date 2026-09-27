/**
 * 菜单点击刷新 / 页签不刷新 行为守卫（CI 检查）
 *
 * 需求（2026-09-28）：
 *   - 点页签切换：不刷新（依赖 KeepAlive 缓存，保留列表状态）
 *   - 点左侧菜单：若目标就是当前页，则重新加载数据
 *
 * 校验：
 *   1. 菜单点击统一入口（routerPushByKeyWithMetaQuery）必须包含「当前页则刷新」的判断；
 *   2. AdminListPage 不得再因 immediate watch 导致首屏重复请求。
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const root = process.cwd();
let failed = 0;
function check(ok, msg) {
  if (ok) console.log(`  ✓ ${msg}`);
  else { console.error(`  ✗ ${msg}`); failed += 1; }
}

console.log('检查 1：菜单点击当前页时触发刷新');
const routerHook = readFileSync(join(root, 'src/hooks/common/router.ts'), 'utf8');
check(
  /route\.value\.name|route\.name/.test(routerHook),
  'routerPushByKeyWithMetaQuery 必须比较目标路由与当前路由'
);
check(
  /reloadPage|resetRouteCache/.test(routerHook),
  '点击当前页菜单必须触发 reloadPage / resetRouteCache'
);

console.log('检查 2：AdminListPage 首屏不重复请求');
const listPage = readFileSync(join(root, 'src/views/_shared/AdminListPage.vue'), 'utf8');
const watchLine = listPage.split('\n').find(line => line.includes('watch(() => props.config'));
check(
  watchLine && !watchLine.includes('immediate: true'),
  'config watch 不得带 immediate:true（否则首屏重复请求）'
);
check(
  /onMounted[\s\S]*?loadData\(\)/.test(listPage),
  '首屏加载仍由 onMounted 负责（保留一次加载）'
);

if (failed > 0) {
  console.error(`\n失败 ${failed} 项`);
  process.exit(1);
}
console.log('\n菜单刷新 / 页签不刷新 行为检查通过');
