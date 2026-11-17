import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');

const migration = read('server/src/main/resources/db/migration/V84__home_hero_config.sql');
assert.ok(migration.includes('home_hero'), 'V84 必须写入 home_hero 配置');
assert.ok(migration.includes('system_home-decor'), 'V84 必须新增首页装修菜单');
assert.ok(migration.includes('/system/home-decor'), '首页装修菜单路径必须固定');
assert.ok(migration.includes('view.system_home-decor'), '首页装修菜单必须绑定前端组件');
assert.ok(migration.includes("'R_OPERATION'"), '首页装修菜单必须授权运营角色');

const controller = read('server/src/main/java/com/wuling/system/controller/AppConfigController.java');
assert.ok(controller.includes('from home_banner'), '首页配置接口必须读取逐条轮播');
assert.ok(controller.includes("status = 'enabled' and deleted = 0"), '首页只能下发启用且未删除的轮播');

const bannerMigration = read('server/src/main/resources/db/migration/V102__home_banner_records.sql');
assert.ok(bannerMigration.includes('CREATE TABLE home_banner'), '迁移必须创建首页轮播表');
assert.ok(bannerMigration.includes('JSON_TABLE'), '迁移必须保留已有多图');
assert.ok(bannerMigration.includes('deleted'), '首页轮播必须支持逻辑删除');

const registry = read('server/src/main/java/com/wuling/system/crud/CrudRegistry.java');
assert.ok(registry.includes('"homeBanners"'), '首页轮播必须登记通用 CRUD 资源');

const routes = read('src/router/elegant/routes.ts');
const imports = read('src/router/elegant/imports.ts');
const transform = read('src/router/elegant/transform.ts');
const routeMeta = read('src/constants/admin.ts');
for (const [source, marker] of [
  [routes, "name: 'system_home-decor'"],
  [routes, "component: 'view.system_home-decor'"],
  [imports, '"system_home-decor": () => import("@/views/system/home-decor/index.vue")'],
  [transform, '"system_home-decor": "/system/home-decor"'],
  [routeMeta, "'system_home-decor': { title: '首页装修'"]
]) {
  assert.ok(source.includes(marker), `后台路由契约缺少: ${marker}`);
}

const page = read('src/views/system/home-decor/index.vue');
const editor = read('src/views/system/home-decor/HomeBannerEditor.vue');
assert.ok(editor.includes('uploadAdminImage'), '首页装修页必须复用通用图片上传 API');
assert.ok(editor.includes('2250'), '首页装修页必须提示推荐宽度');
assert.ok(editor.includes('3120'), '首页装修页必须提示推荐高度');
assert.ok(editor.includes('minWidth: 750') && editor.includes('minHeight: 1040'), '首页图片必须校验原图尺寸');
assert.ok(page.includes('homeBanners'), '首页装修页必须逐条管理轮播');
assert.ok(page.includes('新增轮播图'), '首页装修页必须支持新增');
assert.ok(page.includes('停用') && page.includes('启用'), '首页装修页必须支持启停');
assert.ok(page.includes('store.remove'), '首页装修页删除必须调用通用逻辑删除');

const crud = read('src/service/api/crud.ts');
assert.ok(crud.includes('export async function uploadAdminImage'), '必须新增通用管理员图片上传函数');
assert.ok(crud.includes("url: '/api/v1/files/images'"), '上传函数必须调用文件服务图片接口');

const configPage = read('src/views/system/config/index.vue');
assert.ok(configPage.includes('home_hero:'), '运营配置页必须登记 home_hero 用途');
assert.ok(configPage.includes('已迁移；请到首页装修页维护图片'), '运营配置页必须提示历史配置已迁移');
assert.ok(configPage.includes("'home_hero', 'withdraw_rule'"), '运营配置页必须保护首页配置和提现规则');
assert.ok(
  configPage.includes("!NON_DELETABLE_KEYS.includes(String(row.configKey || ''))"),
  '运营配置页不得允许删除 home_hero'
);

const homeJs = read('user-h5/pages/home/home.js');
assert.ok(homeJs.includes('DEFAULT_HOME_HERO'), '小程序首页必须保留静态默认图常量');
assert.ok(homeJs.includes('resolveAssetUrl'), '小程序首页必须转换文件服务相对 URL');
assert.ok(homeJs.includes('handleHeroError'), '小程序首页必须处理动态图加载失败');

const homeWxml = read('user-h5/pages/home/home.wxml');
assert.ok(homeWxml.includes('wx:for="{{homeBanners}}"'), '首页主视觉必须逐图展示');
assert.ok(homeWxml.includes('src="{{item.imageUrl}}"'), '首页主视觉必须绑定动态 URL');
assert.ok(homeWxml.includes('aria-label="{{item.altText}}"'), '首页主视觉说明必须支持后台配置');
assert.ok(homeWxml.includes('mode="aspectFit"'), '首页主视觉必须等比完整展示');
assert.ok(homeWxml.includes('bindtap="openHomeBanner"'), '运营轮播必须支持点击跳转');
assert.ok(homeWxml.includes('binderror="handleHeroError"'), '首页主视觉必须绑定加载失败回退');

const store = read('src/store/modules/admin/index.ts');
assert.ok(store.includes("homeBanners: 'homeBanners'"), '后台 store 必须登记首页轮播');

console.log('home-decor-admin.test.mjs: passed');
