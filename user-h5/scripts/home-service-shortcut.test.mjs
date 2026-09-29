import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 首页快捷入口跳转口径回归。
 *
 * 背景（真实故障）：后台 /api/v1/app/config/home 下发的 shortcuts 里包含
 * id=service「客服入口」，但 pages/home/home.js#handleShortcut 只实现了
 * stored-value / points-mall 两个分支，客服入口一路落到兜底
 * showUnavailable，点击只弹「客服入口暂未接入」，无法进入客服中心。
 *
 * 锁定：首页 service 入口必须与「我的」页 service 入口跳转完全一致，
 * 且不得被登录拦截。
 */

const homeSource = fs.readFileSync(path.join(root, 'pages/home/home.js'), 'utf8');
const profileSource = fs.readFileSync(path.join(root, 'pages/profile/profile.js'), 'utf8');

const SERVICE_PATH = '/pages/service/service';

// 1) 首页必须显式处理 service 入口
assert.ok(
  /id\s*===\s*'service'/.test(homeSource),
  '首页 handleShortcut 必须包含 service 分支，否则客服入口只弹「暂未接入」'
);

// 2) 跳转路径必须与「我的」页完全一致（同一字符串，避免两边改歪）
assert.ok(
  homeSource.includes("wx.navigateTo({ url: '" + SERVICE_PATH + "' })"),
  '首页 service 入口必须 navigateTo ' + SERVICE_PATH
);
assert.ok(
  profileSource.includes("wx.navigateTo({ url: '" + SERVICE_PATH + "' })"),
  '「我的」页 service 入口必须 navigateTo ' + SERVICE_PATH
);

// 3) service 分支不得包 loginGuard：客服是匿名可用的兜底渠道
const homeServiceBlock = homeSource.match(
  /id\s*===\s*'service'[\s\S]*?\n\s*\}/
);
assert.ok(homeServiceBlock, '未能定位首页 service 分支');
assert.ok(
  !/loginGuard/.test(homeServiceBlock[0]),
  'service 分支不得做登录拦截：匿名用户也必须能联系客服'
);
const profileServiceBlock = profileSource.match(
  /id\s*===\s*'service'[\s\S]*?\n\s*\}/
);
assert.ok(profileServiceBlock, '未能定位「我的」页 service 分支');
assert.ok(
  !/loginGuard/.test(profileServiceBlock[0]),
  '「我的」页 service 分支同样不得做登录拦截（两边口径必须一致）'
);

// 4) 兜底必须保留：后台新增未接入入口时仍要给出明确提示
assert.ok(
  /showUnavailable/.test(homeSource),
  '首页必须保留未接入入口的兜底提示，不能静默无反应'
);

// 5) 客服页必须存在，且不依赖登录态
const servicePage = path.join(root, 'pages/service/service.js');
assert.ok(fs.existsSync(servicePage), '客服页 pages/service/service 必须存在');
const serviceSource = fs.readFileSync(servicePage, 'utf8');
assert.ok(
  !/loginGuard/.test(serviceSource),
  '客服页不得引入登录拦截，否则首页客服入口会在匿名态被拦下'
);

console.log('首页客服入口跳转（与「我的」页一致且不拦截登录）测试通过');