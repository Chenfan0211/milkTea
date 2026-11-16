import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = relativePath => fs.readFileSync(path.join(root, relativePath), 'utf8');

const memberRightsJs = read('pages/member-rights/member-rights.js');
const featureTabJs = read('pages/feature-tab/feature-tab.js');
const profileJs = read('pages/profile/profile.js');
const customTabBarJs = read('custom-tab-bar/index.js');
const appJs = read('app.js');
const navigateJs = read('utils/navigate.js');

assert.ok(memberRightsJs.includes('tabBarConfig.ready(api.fetchConfig)'), '会员权益页必须等待数据库导航配置');
assert.ok(memberRightsJs.includes('tabBarConfig.isMemberZoneTab()'), '会员权益页必须识别会员专区模式');
assert.ok(
  memberRightsJs.includes('wx.switchTab({ url: tabBarConfig.getThirdTabPath() })'),
  '会员专模式必须切到动态第三项'
);
assert.ok(featureTabJs.includes('tabBarConfig.ready(api.fetchConfig)'), '动态宿主页必须先查询数据库配置');
assert.ok(featureTabJs.includes('memberZoneMode'), '宿主页必须按数据库模式选择面板');
assert.ok(customTabBarJs.includes('tabBarConfig.ready(api.fetchConfig)'), '自定义 Tab 必须先查询数据库配置');
assert.ok(customTabBarJs.includes('ready: false'), '配置完成前 TabBar 必须保持隐藏');
assert.ok(appJs.includes('tabBarConfig.ready(api.fetchConfig)'), '应用启动必须非阻塞预取导航配置');
assert.ok(navigateJs.includes('featurePolicy.getTabBarPages()'), '路由工具必须读取版本策略中的 Tab 列表');
assert.ok(
  profileJs.includes('openMemberEntry()') && profileJs.includes('tabBarConfig.getThirdTabPath()'),
  '个人页会员入口必须按数据库模式切换第三项'
);

console.log('member-zone-tab.test.mjs: passed');
