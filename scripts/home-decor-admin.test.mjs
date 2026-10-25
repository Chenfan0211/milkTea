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
assert.ok(controller.includes('readPublicValue("home_hero")'), '首页配置接口必须读取 home_hero');

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
assert.ok(page.includes('uploadAdminImage'), '首页装修页必须复用通用图片上传 API');
assert.ok(page.includes('2250'), '首页装修页必须提示推荐宽度');
assert.ok(page.includes('3120'), '首页装修页必须提示推荐高度');
assert.ok(page.includes('aspect-ratio'), '首页装修页必须提供固定比例预览');
assert.ok(page.includes('home_hero'), '首页装修页必须读写 home_hero 配置');
assert.ok(page.includes('恢复默认图'), '首页装修页必须支持恢复默认图');

const crud = read('src/service/api/crud.ts');
assert.ok(crud.includes('export async function uploadAdminImage'), '必须新增通用管理员图片上传函数');
assert.ok(crud.includes("url: '/api/v1/files/images'"), '上传函数必须调用文件服务图片接口');

const configPage = read('src/views/system/config/index.vue');
assert.ok(configPage.includes('home_hero:'), '运营配置页必须登记 home_hero 用途');
assert.ok(configPage.includes('请到首页装修页维护'), '运营配置页必须提示首页装修页为维护入口');
assert.ok(configPage.includes("PROTECTED_KEYS = ['service_info', 'home_hero']"), '运营配置页必须保护 home_hero');
assert.ok(
  configPage.includes("!PROTECTED_KEYS.includes(String(row.configKey || ''))"),
  '运营配置页不得允许删除 home_hero'
);

const homeJs = read('user-h5/pages/home/home.js');
assert.ok(homeJs.includes('DEFAULT_HOME_HERO'), '小程序首页必须保留静态默认图常量');
assert.ok(homeJs.includes('resolveAssetUrl'), '小程序首页必须转换文件服务相对 URL');
assert.ok(homeJs.includes('handleHeroError'), '小程序首页必须处理动态图加载失败');

const homeWxml = read('user-h5/pages/home/home.wxml');
assert.ok(homeWxml.includes('src="{{homeHeroUrl}}"'), '首页主视觉必须绑定动态 URL');
assert.ok(homeWxml.includes('aria-label="{{homeHeroAlt}}"'), '首页主视觉说明必须支持后台配置');
assert.ok(homeWxml.includes('mode="aspectFill"'), '首页主视觉必须固定比例裁切');
assert.ok(homeWxml.includes('binderror="handleHeroError"'), '首页主视觉必须绑定加载失败回退');

console.log('home-decor-admin.test.mjs: passed');
