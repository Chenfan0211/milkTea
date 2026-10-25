import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');

const migration = read('server/src/main/resources/db/migration/V85__profile_banner.sql');
assert.ok(migration.includes('CREATE TABLE profile_banner'), 'V85 必须创建 profile_banner 表');
assert.ok(migration.includes('image_url'), '轮播表必须保存图片 URL');
assert.ok(migration.includes('alt_text'), '轮播表必须保存图片说明');
assert.ok(migration.includes('/assets/images/3x/profile-banner.jpg'), 'V85 必须初始化当前默认横幅');
assert.ok(migration.includes('marketing_profile-banner'), 'V85 必须新增我的页轮播菜单');
assert.ok(migration.includes('/marketing/profile-banner'), '轮播菜单路径必须固定');
assert.ok(migration.includes('view.marketing_profile-banner'), '轮播菜单必须绑定前端组件');
assert.ok(migration.includes("'R_OPERATION'"), '轮播菜单必须授权运营角色');

const registry = read('server/src/main/java/com/wuling/system/crud/CrudRegistry.java');
assert.ok(registry.includes('"profileBanners"'), '后台 CRUD 必须登记 profileBanners 资源');
assert.ok(registry.includes('"profile_banner"'), 'profileBanners 必须绑定 profile_banner 表');
assert.ok(registry.includes('"image_url", "alt_text", "sort", "status"'), '轮播可写字段必须显式登记');

const controller = read('server/src/main/java/com/wuling/system/controller/AppConfigController.java');
assert.ok(controller.includes('from profile_banner'), '我的页配置接口必须查询 profile_banner');
assert.ok(controller.includes("status = 'enabled'"), '接口只能下发启用状态的轮播');
assert.ok(controller.includes('order by sort asc, id asc'), '轮播必须按排序和 ID 返回');

const routes = read('src/router/elegant/routes.ts');
const imports = read('src/router/elegant/imports.ts');
const transform = read('src/router/elegant/transform.ts');
const routeMeta = read('src/constants/admin.ts');
const routerTypes = read('src/typings/elegant-router.d.ts');
for (const [source, marker] of [
  [routes, "name: 'marketing_profile-banner'"],
  [routes, "component: 'view.marketing_profile-banner'"],
  [imports, '"marketing_profile-banner": () => import("@/views/marketing/profile-banner/index.vue")'],
  [transform, '"marketing_profile-banner": "/marketing/profile-banner"'],
  [routeMeta, "'marketing_profile-banner': { title: '我的页轮播'"],
  [routerTypes, '"marketing_profile-banner": "/marketing/profile-banner"']
]) {
  assert.ok(source.includes(marker), `后台轮播路由契约缺少: ${marker}`);
}

const page =
  read('src/views/marketing/profile-banner/index.vue') +
  read('src/views/marketing/profile-banner/ProfileBannerEditor.vue');
assert.ok(page.includes('uploadAdminImage'), '轮播后台必须复用通用图片上传 API');
assert.ok(page.includes('profileBanners'), '轮播后台必须读写 profileBanners 资源');
assert.ok(page.includes('2130'), '轮播后台必须提示推荐宽度');
assert.ok(page.includes('480'), '轮播后台必须提示推荐高度');
assert.ok(page.includes('status'), '轮播后台必须支持启用停用');

const store = read('src/store/modules/admin/index.ts');
assert.ok(store.includes("profileBanners: 'profileBanners'"), '后台 store 必须登记 profileBanners 远端资源');

console.log('profile-banner-admin.test.mjs: passed');
