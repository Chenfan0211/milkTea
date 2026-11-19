import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');
const exists = relativePath => fs.existsSync(path.join(root, relativePath));

const migration = read('server/src/main/resources/db/migration/V108__mall_banner.sql');
assert.match(migration, /CREATE TABLE(?: IF NOT EXISTS)? mall_banner/, 'V108 必须创建商城轮播表');
for (const column of ['image_url', 'alt_text', 'target_type', 'target_id', 'sort', 'status', 'deleted']) {
  assert.ok(migration.includes(column), `商城轮播表缺少字段: ${column}`);
}
assert.ok(migration.includes("target_type IN ('none', 'mallProduct')"), '商城轮播必须限制跳转类型');
assert.ok(migration.includes("'mall_banner'"), 'V108 必须新增商城轮播菜单编码');
assert.ok(migration.includes("'/mall/banner'"), '商城轮播菜单路径必须固定');
assert.ok(migration.includes("'view.mall_banner'"), '商城轮播菜单必须绑定前端组件');
assert.ok(migration.includes("'R_OPERATION'"), '商城轮播菜单必须授权运营角色');

const controller = read('server/src/main/java/com/wuling/system/controller/AppConfigController.java');
assert.ok(controller.includes('@GetMapping("/mall")'), '服务端必须提供商城配置接口');
assert.ok(controller.includes('from mall_banner'), '商城配置必须读取独立轮播表');
assert.ok(controller.includes("status = 'enabled' and deleted = 0"), '商城只能下发启用且未删除的轮播');
assert.ok(controller.includes('order by sort asc, id asc'), '商城轮播必须按排序返回');

const registry = read('server/src/main/java/com/wuling/system/crud/CrudRegistry.java');
assert.ok(registry.includes('"mallBanners"'), '后台必须登记 mallBanners 通用 CRUD 资源');
assert.ok(registry.includes('"mall_banner"'), 'mallBanners 必须绑定 mall_banner 表');
assert.ok(registry.includes('"target_type", "target_id"'), '商城轮播跳转字段必须可维护');

const routerContracts = [
  ['src/router/elegant/routes.ts', "name: 'mall_banner'"],
  ['src/router/elegant/routes.ts', "path: '/mall/banner'"],
  ['src/router/elegant/routes.ts', "component: 'view.mall_banner'"],
  ['src/router/elegant/imports.ts', 'mall_banner: () => import("@/views/mall/banner/index.vue")'],
  ['src/router/elegant/transform.ts', '"mall_banner": "/mall/banner"'],
  ['src/typings/elegant-router.d.ts', '"mall_banner": "/mall/banner"'],
  ['src/constants/admin.ts', "mall_banner: { title: '商城轮播'"],
  ['src/locales/langs/zh-cn.ts', "mall_banner: '商城轮播'"],
  ['src/locales/langs/en-us.ts', "mall_banner: 'Mall Carousel'"]
];
for (const [relativePath, marker] of routerContracts) {
  assert.ok(read(relativePath).includes(marker), `${relativePath} 缺少商城轮播契约: ${marker}`);
}

const store = read('src/store/modules/admin/index.ts');
assert.ok(store.includes("mallBanners: 'mallBanners'"), '后台 store 必须登记商城轮播资源');
assert.ok(store.includes('mallBanners: []'), '后台 store 必须初始化商城轮播列表');
assert.ok(store.includes("mallBanners: '商城轮播'"), '后台审计日志必须识别商城轮播模块');

assert.ok(exists('src/views/mall/banner/index.vue'), '必须新增商城轮播列表页');
assert.ok(exists('src/views/mall/banner/MallBannerEditor.vue'), '必须新增商城轮播编辑器');
const page = read('src/views/mall/banner/index.vue');
const editor = read('src/views/mall/banner/MallBannerEditor.vue');
assert.ok(page.includes("remoteKey: 'mallBanners'"), '商城轮播页必须读写 mallBanners');
assert.ok(page.includes('新增轮播图'), '商城轮播页必须支持新增');
assert.ok(page.includes('启用') && page.includes('停用'), '商城轮播页必须支持启停');
assert.ok(page.includes('store.remove'), '商城轮播页删除必须走通用逻辑删除');
assert.ok(editor.includes('uploadAdminImage'), '商城轮播图片必须复用管理员上传接口');
assert.ok(editor.includes('maxWidth: 1420') && editor.includes('maxHeight: 520'), '商城轮播压缩尺寸必须匹配宽幅比例');
assert.ok(editor.includes('minWidth: 710') && editor.includes('minHeight: 260'), '商城轮播必须校验最小原图尺寸');
assert.ok(editor.includes('2130×780'), '商城轮播必须提示 3 倍推荐尺寸');
assert.ok(editor.includes('allowedTypes = ['), '商城轮播编辑器必须限制跳转选项');
assert.ok(editor.includes("['none', 'mallProduct']"), '商城轮播只允许不跳转和商城商品');

const targetFields = read('src/views/_shared/BannerTargetFields.vue');
assert.ok(targetFields.includes('allowedTypes'), '共享跳转组件必须支持按页面限制选项');

console.log('mall-banner-admin.test.mjs: passed');
