import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
const { buildMiniProgram } = await import(pathToFileURL(path.join(root, 'scripts/build-miniprogram.mjs')).href);

const policy = {
  schemaVersion: 1,
  features: {
    tongueHealth: {
      hiddenVersions: ['1.3.10'],
      replacement: 'memberZone'
    }
  }
};

function tempOutput(label) {
  return path.join(os.tmpdir(), `milktea-${label}-${process.pid}-${Date.now()}`);
}

function listTextFiles(dir) {
  const result = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) result.push(...listTextFiles(full));
    else if (/\.(js|json|wxml|wxss)$/i.test(entry.name)) result.push(full);
  }
  return result;
}

const hiddenOutput = tempOutput('hidden');
const visibleOutput = tempOutput('visible');

try {
  await buildMiniProgram({
    sourceRoot: root,
    outputDir: hiddenOutput,
    version: '1.3.10',
    policy,
    quiet: true
  });

  const hiddenApp = JSON.parse(fs.readFileSync(path.join(hiddenOutput, 'app.json'), 'utf8'));
  assert.ok(!hiddenApp.pages.includes('pages/tongue/tongue'), '隐藏版本不得注册舌诊主包页');
  assert.ok(!hiddenApp.subpackages.some(item => item.root === 'packageHealth'), '隐藏版本不得注册 packageHealth 分包');
  assert.deepEqual(
    hiddenApp.tabBar.list.map(item => [item.pagePath, item.text]),
    [
      ['pages/home/home', '首页'],
      ['pages/menu/menu', '点单'],
      ['pages/member-rights/member-rights', '会员专区'],
      ['pages/orders/orders', '订单'],
      ['pages/profile/profile', '我的']
    ],
    '隐藏版本第三个 Tab 必须替换为会员专区'
  );

  for (const relativePath of [
    'pages/tongue/tongue.js',
    'packageHealth/pages/capture/capture.js',
    'components/health-profile-sheet/health-profile-sheet.js',
    'utils/tongue-profile.js',
    'utils/tongue-capture.js',
    'utils/tongue-report-model.js',
    'assets/icons/lucide/personalized-drink.svg',
    'assets/images/3x/health-gradient-bg.png'
  ]) {
    assert.equal(fs.existsSync(path.join(hiddenOutput, relativePath)), false, `隐藏版本不得包含 ${relativePath}`);
  }

  const forbidden = /AI生成|人工智能生成|舌象|舌诊|体质健康管理|一人一饮/;
  for (const file of listTextFiles(hiddenOutput)) {
    const content = fs.readFileSync(file, 'utf8');
    assert.ok(!forbidden.test(content), `隐藏产物仍包含禁词: ${path.relative(hiddenOutput, file)}`);
    assert.ok(!content.includes('@mini-release:'), `构建产物不得残留功能标记: ${path.relative(hiddenOutput, file)}`);
  }

  const hiddenPolicyPath = path.join(hiddenOutput, 'config/build-policy.js');
  delete require.cache[require.resolve(hiddenPolicyPath)];
  const hiddenBuildPolicy = require(hiddenPolicyPath);
  assert.equal(hiddenBuildPolicy.version, '1.3.10', '构建产物必须冻结构建版本号');
  assert.equal(hiddenBuildPolicy.features.tongueHealth, false, '隐藏版本必须关闭舌诊能力');
  assert.equal(hiddenBuildPolicy.features.memberZone, true, '隐藏版本必须开启会员专区 Tab');

  await buildMiniProgram({
    sourceRoot: root,
    outputDir: visibleOutput,
    version: '1.3.11',
    policy,
    quiet: true
  });
  const visibleApp = JSON.parse(fs.readFileSync(path.join(visibleOutput, 'app.json'), 'utf8'));
  assert.ok(visibleApp.pages.includes('pages/tongue/tongue'), '未隐藏版本必须保留舌诊主包页');
  assert.equal(visibleApp.tabBar.list[2].pagePath, 'pages/tongue/tongue', '未隐藏版本第三个 Tab 必须仍为舌诊');
  assert.ok(
    fs.existsSync(path.join(visibleOutput, 'packageHealth/pages/report/report.js')),
    '未隐藏版本必须保留舌诊分包'
  );
  delete require.cache[require.resolve(path.join(visibleOutput, 'config/build-policy.js'))];
  const visibleBuildPolicy = require(path.join(visibleOutput, 'config/build-policy.js'));
  assert.equal(visibleBuildPolicy.features.tongueHealth, true, '未隐藏版本必须保留舌诊能力');
  assert.equal(visibleBuildPolicy.features.memberZone, false, '未隐藏版本不得占用会员专区 Tab');

  console.log('build-miniprogram.test.mjs: passed');
} finally {
  for (const dir of [hiddenOutput, visibleOutput]) {
    const resolved = path.resolve(dir);
    if (resolved.startsWith(path.resolve(os.tmpdir()))) fs.rmSync(resolved, { recursive: true, force: true });
  }
}
