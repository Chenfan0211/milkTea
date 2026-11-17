import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');
const exists = relativePath => fs.existsSync(path.join(root, relativePath));

const app = JSON.parse(read('app.json'));
assert.ok(app.pages.includes('pages/feature-tab/feature-tab'), '必须注册动态第三项宿主页');
assert.deepEqual(
  app.tabBar.list.map(item => [item.pagePath, item.text]),
  [
    ['pages/home/home', '首页'],
    ['pages/menu/menu', '点单'],
    ['pages/feature-tab/feature-tab', '会员专区'],
    ['pages/orders/orders', '订单'],
    ['pages/profile/profile', '我的']
  ],
  '第三个静态 Tab 必须固定指向动态宿主页'
);
assert.ok(app.pages.includes('pages/tongue/tongue'), '舌诊页仍需保留给反向切换使用');
assert.ok(app.pages.includes('pages/member-rights/member-rights'), '会员页仍需保留给普通页面入口');

const tabBarJs = read('custom-tab-bar/index.js');
const tabBarWxml = read('custom-tab-bar/index.wxml');
assert.match(tabBarJs, /ready:\s*false/, '配置查询完成前 TabBar 必须保持未就绪');
assert.match(tabBarJs, /tabBarConfig\.ready\(api\.fetchConfig\)/, 'TabBar 必须先查询数据库配置');
assert.match(tabBarWxml, /wx:if="\{\{ready && !hidden\}\}"/, '配置查询完成前不得显示 TabBar');

const featurePage = read('pages/feature-tab/feature-tab.wxml');
assert.match(featurePage, /<tongue-home-panel[^>]*wx:if=/, '宿主页必须按数据库模式渲染一人一饮面板');
assert.match(featurePage, /wx:elif="\{\{ready\}\}"[\s\S]*<member-panel/, '宿主页必须按数据库模式渲染会员面板');
assert.ok(exists('components/tongue-home-panel/tongue-home-panel.wxml'), '舌诊首页必须抽成可复用组件');

const initialMigration = read('../server/src/main/resources/db/migration/V97__mini_tab_bar_config.sql');
const upgradeMigration = read('../server/src/main/resources/db/migration/V106__upgrade_mini_tab_bar_config.sql');
assert.match(initialMigration, /mini_tab_bar/, 'V97 必须保留已执行的导航配置迁移');
assert.match(initialMigration, /"schemaVersion":1/, '已执行的 V97 不能改写');
assert.match(upgradeMigration, /'schemaVersion',\s*2/, 'V106 必须升级至 schema v2');
assert.match(upgradeMigration, /'defaultThirdTab'/, 'V106 必须保留默认第三项');
assert.match(upgradeMigration, /'1\.3\.5',\s*'memberZone'/, 'V106 必须预置 1.3.5 版本映射');
assert.match(upgradeMigration, /JSON_EXTRACT\(value, '\$\.schemaVersion'\)/, 'V106 只升级旧版配置');

console.log('tab-bar-runtime-integration.test.mjs: passed');
