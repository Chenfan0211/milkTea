/**
 * 路由缓存名单守卫（CI 检查）
 *
 * 背景（2026-09-28 真实 bug）：
 *   VITE_AUTH_ROUTE_MODE=dynamic 下，业务路由由后端 /route/getUserRoutes 下发，
 *   而 sys_menu 表没有 keep_alive 字段，导致下发的 meta 里没有 keepAlive。
 *   旧 getCacheRouteNames 只认 child.meta?.keepAlive，于是业务列表页全部
 *   不在缓存名单里 -> 切页签时组件被销毁重建 -> 重新请求 + 搜索条件被清空。
 *
 * 修复：缓存判定改为「有 component 且非 hideInMenu 的叶子路由」，
 *   与 static 模式下 keepAlive:true 的语义一致，且不依赖后端字段。
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

const src = readFileSync(join(root, 'src/store/modules/route/shared.ts'), 'utf8');

console.log('检查 1：缓存判定不依赖 meta.keepAlive');
check(
  !/child\.component && child\.meta\?\.keepAlive/.test(src),
  '缓存条件不得是「component 且 meta.keepAlive」（dynamic 路由没有 keepAlive 字段）'
);

console.log('检查 2：缓存「有 component 且非 hideInMenu」的叶子路由');
check(/child\.component/.test(src), '必须仍有 child.component 判定');
check(/hideInMenu/.test(src), '必须排除 hideInMenu 的详情页（详情页每次进入应重新加载）');

if (failed > 0) {
  console.error(`\n失败 ${failed} 项`);
  process.exit(1);
}
console.log('\n路由缓存名单守卫检查通过');
