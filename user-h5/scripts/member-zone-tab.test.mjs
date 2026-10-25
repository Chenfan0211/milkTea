import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');

const memberRightsJs = read('pages/member-rights/member-rights.js');
const memberRightsWxml = read('pages/member-rights/member-rights.wxml');
const profileJs = read('pages/profile/profile.js');
const customTabBarJs = read('custom-tab-bar/index.js');
const appJs = read('app.js');
const navigateJs = read('utils/navigate.js');

assert.ok(memberRightsJs.includes('featurePolicy.isMemberZoneTab()'), '会员权益页必须读取会员专区 Tab 模式');
assert.ok(memberRightsJs.includes('selected: 2'), '会员专区作为 Tab 时必须同步第三项选中态');
assert.ok(memberRightsJs.includes('navigationTitle'), '会员专区标题必须由运行模式决定');
assert.ok(memberRightsWxml.includes('title="{{navigationTitle}}"'), '导航标题必须使用运行模式标题');
assert.ok(memberRightsWxml.includes('back="{{showBack}}"'), '会员专区 Tab 模式必须隐藏返回键');
assert.ok(customTabBarJs.includes('featurePolicy.getTabBarItems()'), '自定义 Tab 必须读取版本策略列表');
assert.ok(appJs.includes('featurePolicy.refreshRemote(api.fetchConfig)'), '应用启动必须非阻塞刷新远程版本策略');
assert.ok(navigateJs.includes('featurePolicy.getTabBarPages()'), '路由工具必须读取版本策略中的 Tab 列表');
assert.ok(
  profileJs.includes("navigate.open('/pages/member-rights/member-rights')"),
  '个人页会员入口必须通过统一路由兼容会员专区 Tab 模式'
);

console.log('member-zone-tab.test.mjs: passed');
