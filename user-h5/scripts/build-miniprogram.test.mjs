import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const { buildMiniProgram } = await import(pathToFileURL(path.join(root, 'scripts/build-miniprogram.mjs')).href);
const outputDir = path.join(os.tmpdir(), `milktea-runtime-tabs-${process.pid}-${Date.now()}`);

function listTextFiles(dir) {
  const result = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) result.push(...listTextFiles(full));
    else if (/\.(js|json|wxml|wxss)$/i.test(entry.name)) result.push(full);
  }
  return result;
}

try {
  await buildMiniProgram({
    sourceRoot: root,
    outputDir,
    version: 'runtime-tab-bar-test',
    quiet: true
  });

  const app = JSON.parse(fs.readFileSync(path.join(outputDir, 'app.json'), 'utf8'));
  assert.deepEqual(
    app.tabBar.list.map(item => [item.pagePath, item.text]),
    [
      ['pages/home/home', '首页'],
      ['pages/menu/menu', '点单'],
      ['pages/feature-tab/feature-tab', '会员专区'],
      ['pages/orders/orders', '订单'],
      ['pages/profile/profile', '我的']
    ],
    '运行时可切换产物的第三个 Tab 必须固定指向宿主页'
  );
  assert.ok(app.pages.includes('pages/feature-tab/feature-tab'), '必须打包动态宿主页');
  assert.ok(app.pages.includes('pages/tongue/tongue'), '必须保留舌诊兼容入口');
  assert.ok(app.pages.includes('pages/member-rights/member-rights'), '必须保留会员权益普通页面');
  assert.ok(
    app.subpackages.some(item => item.root === 'packageHealth'),
    '双向切换产物必须保留 packageHealth 分包'
  );
  for (const relativePath of [
    'components/tongue-home-panel/tongue-home-panel.js',
    'components/member-panel/member-panel.js',
    'utils/tab-bar-config.js',
    'packageHealth/pages/report/report.js'
  ]) {
    assert.ok(fs.existsSync(path.join(outputDir, relativePath)), `双向切换产物缺少 ${relativePath}`);
  }

  const policyPath = path.join(outputDir, 'config/build-policy.js');
  delete require.cache[require.resolve(policyPath)];
  const policy = require(policyPath);
  assert.equal(policy.version, 'runtime-tab-bar-test');
  assert.equal(policy.features.tongueHealth, true);
  assert.equal(policy.features.memberZone, true);
  assert.equal(policy.tabBar[2].pagePath, '/pages/feature-tab/feature-tab');

  for (const file of listTextFiles(outputDir)) {
    const content = fs.readFileSync(file, 'utf8');
    assert.ok(!content.includes('@mini-release:'), `构建产物不得残留功能标记: ${path.relative(outputDir, file)}`);
  }
  for (const relativePath of ['assets/temp', 'design', 'docs', 'scripts']) {
    assert.equal(fs.existsSync(path.join(outputDir, relativePath)), false, `构建产物不得包含 ${relativePath}`);
  }
  assert.ok(
    fs.existsSync(path.join(outputDir, 'assets/images/3x/health-gradient-bg.png')),
    '运行图必须随双向切换能力保留'
  );

  console.log('build-miniprogram.test.mjs: passed');
} finally {
  const resolved = path.resolve(outputDir);
  if (resolved.startsWith(path.resolve(os.tmpdir()))) fs.rmSync(resolved, { recursive: true, force: true });
}
