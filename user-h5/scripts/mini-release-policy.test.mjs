import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(root, '..');
const require = createRequire(import.meta.url);
const featurePolicy = require(path.join(root, 'utils/feature-policy.js'));

const remotePolicy = {
  schemaVersion: 1,
  features: {
    tongueHealth: {
      hiddenVersions: [' 1.3.10 ', '1.3.10', '1.3.12'],
      replacement: 'memberZone'
    }
  }
};

assert.equal(featurePolicy.isVersionHidden(remotePolicy, '1.3.10'), true, '指定构建版本必须精确命中隐藏规则');
assert.equal(featurePolicy.isVersionHidden(remotePolicy, '1.3.11'), false, '未指定的构建版本不得隐藏');
assert.deepEqual(
  featurePolicy.getHiddenVersions(remotePolicy, 'tongueHealth'),
  ['1.3.10', '1.3.12'],
  '版本列表必须去空格并按首次出现顺序去重'
);

assert.throws(
  () =>
    featurePolicy.normalizeRemotePolicy({ features: { tongueHealth: { hiddenVersions: [], replacement: 'unknown' } } }),
  /replacement/,
  '未知 Tab 替换项必须在解析阶段拒绝'
);
assert.deepEqual(
  featurePolicy.normalizeRemotePolicy({
    features: { tongueHealth: { hiddenVersions: null, replacement: 'memberZone' } }
  }).features.tongueHealth.hiddenVersions,
  [],
  '空版本列表必须规范化为空数组'
);

const visibleBuild = {
  version: '1.3.11',
  features: { tongueHealth: true, memberZone: false },
  tabBar: [
    { pagePath: '/pages/home/home', text: '首页' },
    { pagePath: '/pages/menu/menu', text: '点单' },
    { pagePath: '/pages/tongue/tongue', text: '一人一饮', feature: 'tongueHealth' },
    { pagePath: '/pages/orders/orders', text: '订单' },
    { pagePath: '/pages/profile/profile', text: '我的' }
  ]
};
assert.deepEqual(
  featurePolicy.resolveFeatureVisibility(visibleBuild, remotePolicy, '1.3.11'),
  { tongueHealth: true, memberZone: false },
  '未命中隐藏版本时保留舌诊能力'
);

const hiddenBuild = {
  version: '1.3.10',
  features: { tongueHealth: false, memberZone: true },
  tabBar: [
    { pagePath: '/pages/home/home', text: '首页' },
    { pagePath: '/pages/menu/menu', text: '点单' },
    { pagePath: '/pages/member-rights/member-rights', text: '会员专区', feature: 'memberZone' },
    { pagePath: '/pages/orders/orders', text: '订单' },
    { pagePath: '/pages/profile/profile', text: '我的' }
  ]
};
const hiddenVisibility = featurePolicy.resolveFeatureVisibility(hiddenBuild, remotePolicy, '1.3.10');
assert.deepEqual(hiddenVisibility, { tongueHealth: false, memberZone: true }, '隐藏版本必须启用会员专区替换项');
assert.deepEqual(
  featurePolicy.selectTabBarItems(hiddenBuild, hiddenVisibility).map(item => item.text),
  ['首页', '点单', '会员专区', '订单', '我的'],
  '隐藏版本第三个 Tab 必须替换为会员专区'
);
assert.equal(featurePolicy.shouldHideRoute('/pages/tongue/tongue', hiddenVisibility), true, '隐藏版本必须拦截舌诊 Tab');
assert.equal(
  featurePolicy.shouldHideRoute('/packageHealth/pages/report/report?id=8', hiddenVisibility),
  true,
  '隐藏版本必须拦截舌诊分包页'
);
assert.equal(
  featurePolicy.shouldHideRoute('/pages/member-rights/member-rights', hiddenVisibility),
  false,
  '会员专区不能被路由守卫误拦截'
);

const migration = fs.readFileSync(
  path.join(repoRoot, 'server/src/main/resources/db/migration/V83__mini_release_policy.sql'),
  'utf8'
);
assert.ok(migration.includes('mini_release_policy'), 'V83 必须写入版本策略配置键');
assert.ok(migration.includes('1.3.10'), 'V83 必须预置 1.3.10 隐藏规则');

console.log('mini-release-policy.test.mjs: passed');
