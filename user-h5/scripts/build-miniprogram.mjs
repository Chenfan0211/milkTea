import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
const featurePolicy = require('../utils/feature-policy.js');
const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const defaultSourceRoot = path.resolve(scriptDir, '..');
const ALWAYS_SKIP = new Set([
  '.git',
  '.idea',
  '.codex-logs',
  'node_modules',
  'dist',
  'docs',
  'design',
  'scripts',
  'assets/temp'
]);
const HIDDEN_SKIP = [
  'pages/tongue',
  'packageHealth',
  'components/health-profile-sheet',
  'utils/tongue-profile.js',
  'utils/tongue-capture.js',
  'utils/tongue-report-model.js',
  'assets/icons/lucide/personalized-drink.svg',
  'assets/icons/lucide/personalized-drink-active.svg',
  'assets/icons/lucide/scan-face-brand.svg',
  'assets/icons/lucide/coffee-brand.svg',
  'assets/icons/lucide/brew-record-brand.svg',
  'assets/icons/lucide/glass-water-brand.svg',
  'assets/images/3x/health-gradient-bg.png',
  'assets/images/3x/tongue-capture-guide.jpg'
];
const TEXT_EXTENSIONS = new Set(['.js', '.json', '.wxml', '.wxss']);
const FORBIDDEN_COPY = /AI生成|人工智能生成|舌象|舌诊|体质健康管理|一人一饮/;

function toPosix(value) {
  return String(value || '').replaceAll('\\', '/');
}

function isSkipped(relativePath, hidden) {
  const normalized = toPosix(relativePath).replace(/^\/+|\/+$/g, '');
  if (!normalized) return false;
  const segments = normalized.split('/');
  if (segments.some(segment => ALWAYS_SKIP.has(segment))) return true;
  if (!hidden) return false;
  return HIDDEN_SKIP.some(item => normalized === item || normalized.startsWith(item + '/'));
}

function processFeatureMarkers(content, visibility) {
  const lines = content.split(/\r?\n/);
  const output = [];
  let activeFeature = '';
  let skipping = false;

  for (const line of lines) {
    const start = line.match(/@mini-release:([A-Za-z0-9_-]+):start/);
    if (start) {
      if (activeFeature) throw new Error(`功能标记嵌套: ${activeFeature} -> ${start[1]}`);
      activeFeature = start[1];
      skipping = visibility[activeFeature] === false;
      continue;
    }

    const end = line.match(/@mini-release:([A-Za-z0-9_-]+):end/);
    if (end) {
      if (!activeFeature || activeFeature !== end[1]) {
        throw new Error(`功能标记不匹配: ${activeFeature || '(none)'} -> ${end[1]}`);
      }
      activeFeature = '';
      skipping = false;
      continue;
    }

    if (!skipping) output.push(line);
  }

  if (activeFeature) throw new Error(`功能标记未闭合: ${activeFeature}`);
  return output.join('\n');
}

function copyTree(sourceDir, outputDir, visibility, rootDir) {
  const hidden = visibility.tongueHealth === false;
  fs.mkdirSync(outputDir, { recursive: true });

  for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
    const sourcePath = path.join(sourceDir, entry.name);
    const relativePath = path.relative(rootDir, sourcePath);
    if (isSkipped(relativePath, hidden)) continue;

    const targetPath = path.join(outputDir, entry.name);
    if (entry.isDirectory()) {
      copyTree(sourcePath, targetPath, visibility, rootDir);
      continue;
    }

    const extension = path.extname(entry.name).toLowerCase();
    if (TEXT_EXTENSIONS.has(extension)) {
      const source = fs.readFileSync(sourcePath, 'utf8');
      fs.writeFileSync(targetPath, processFeatureMarkers(source, visibility), 'utf8');
      continue;
    }
    fs.copyFileSync(sourcePath, targetPath);
  }
}

function createBuildPolicy(version, hidden) {
  const tongueTab = {
    pagePath: '/pages/tongue/tongue',
    text: '一人一饮',
    icon: '/assets/icons/lucide/personalized-drink.svg',
    activeIcon: '/assets/icons/lucide/personalized-drink-active.svg',
    feature: 'tongueHealth'
  };
  const memberTab = {
    pagePath: '/pages/member-rights/member-rights',
    text: '会员专区',
    icon: '/assets/icons/lucide/member.svg',
    activeIcon: '/assets/icons/lucide/member-active.svg',
    feature: 'memberZone'
  };

  return {
    version,
    features: {
      tongueHealth: !hidden,
      memberZone: hidden
    },
    tabBar: [
      {
        pagePath: '/pages/home/home',
        text: '首页',
        icon: '/assets/icons/lucide/home.svg',
        activeIcon: '/assets/icons/lucide/home-active.svg'
      },
      {
        pagePath: '/pages/menu/menu',
        text: '点单',
        icon: '/assets/icons/lucide/menu.svg',
        activeIcon: '/assets/icons/lucide/menu-active.svg'
      },
      hidden ? memberTab : tongueTab,
      {
        pagePath: '/pages/orders/orders',
        text: '订单',
        icon: '/assets/icons/lucide/orders.svg',
        activeIcon: '/assets/icons/lucide/orders-active.svg'
      },
      {
        pagePath: '/pages/profile/profile',
        text: '我的',
        icon: '/assets/icons/lucide/profile.svg',
        activeIcon: '/assets/icons/lucide/profile-active.svg'
      }
    ]
  };
}

function writeBuildPolicy(outputDir, policy) {
  const target = path.join(outputDir, 'config', 'build-policy.js');
  const source = `module.exports = ${JSON.stringify(policy, null, 2)};\n`;
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, source, 'utf8');
}

function rewriteAppJson(outputDir, hidden) {
  const appJsonPath = path.join(outputDir, 'app.json');
  const appJson = JSON.parse(fs.readFileSync(appJsonPath, 'utf8'));
  if (!hidden) return;
  appJson.pages = appJson.pages.filter(page => page !== 'pages/tongue/tongue');
  appJson.subpackages = (appJson.subpackages || []).filter(item => item.root !== 'packageHealth');
  appJson.tabBar.list = appJson.tabBar.list.map(item => {
    if (item.pagePath !== 'pages/tongue/tongue') return item;
    return {
      pagePath: 'pages/member-rights/member-rights',
      text: '会员专区'
    };
  });
  fs.writeFileSync(appJsonPath, JSON.stringify(appJson, null, 2) + '\n', 'utf8');
}

function writePackageJson(outputDir, version) {
  const target = path.join(outputDir, 'package.json');
  const data = {
    name: 'wuling-shiguang-miniprogram',
    version,
    private: true,
    type: 'commonjs'
  };
  fs.writeFileSync(target, JSON.stringify(data, null, 2) + '\n', 'utf8');
}

function walkTextFiles(dir) {
  const files = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...walkTextFiles(full));
    else if (TEXT_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) files.push(full);
  }
  return files;
}

function validateOutput(outputDir, visibility) {
  const appJson = JSON.parse(fs.readFileSync(path.join(outputDir, 'app.json'), 'utf8'));
  const thirdTab = appJson.tabBar.list[2];
  if (visibility.tongueHealth === false) {
    if (appJson.pages.includes('pages/tongue/tongue')) throw new Error('隐藏产物仍注册舌诊主包页');
    if ((appJson.subpackages || []).some(item => item.root === 'packageHealth')) {
      throw new Error('隐藏产物仍注册 packageHealth 分包');
    }
    if (!thirdTab || thirdTab.pagePath !== 'pages/member-rights/member-rights' || thirdTab.text !== '会员专区') {
      throw new Error('隐藏产物第三个 Tab 不是会员专区');
    }
  }

  for (const file of walkTextFiles(outputDir)) {
    const content = fs.readFileSync(file, 'utf8');
    if (content.includes('@mini-release:')) throw new Error(`构建产物残留功能标记: ${path.relative(outputDir, file)}`);
    if (visibility.tongueHealth === false && FORBIDDEN_COPY.test(content)) {
      throw new Error(`隐藏产物残留禁词: ${path.relative(outputDir, file)}`);
    }
  }
}

function assertSafeOutput(outputDir, sourceRoot) {
  const target = path.resolve(outputDir);
  const source = path.resolve(sourceRoot);
  const sourceDist = path.join(source, 'dist');
  const temp = path.resolve(os.tmpdir());
  const inSourceDist = target === sourceDist || target.startsWith(sourceDist + path.sep);
  const inTemp = target === temp || target.startsWith(temp + path.sep);
  if (!inSourceDist && !inTemp) {
    throw new Error(`输出目录必须位于 ${sourceDist} 或系统临时目录: ${target}`);
  }
}

function normalizeConfigPayload(body) {
  let value = body;
  if (value && typeof value === 'object' && Object.prototype.hasOwnProperty.call(value, 'data')) value = value.data;
  if (typeof value === 'string') value = JSON.parse(value);
  return value;
}

async function fetchReleasePolicy(baseUrl, timeoutMs = 15000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const base = String(baseUrl || '').replace(/\/+$/, '');
  try {
    const response = await fetch(`${base}/api/v1/app/config/mini_release_policy`, { signal: controller.signal });
    if (!response.ok) throw new Error(`读取版本策略失败: HTTP ${response.status}`);
    return normalizeConfigPayload(await response.json());
  } finally {
    clearTimeout(timer);
  }
}

async function buildMiniProgram(options) {
  const sourceRoot = path.resolve(options.sourceRoot || defaultSourceRoot);
  const version = String(options.version || '').trim();
  if (!version) throw new Error('缺少构建版本号 --version');
  const remotePolicy = featurePolicy.normalizeRemotePolicy(options.policy);
  const hidden = featurePolicy.isVersionHidden(remotePolicy, version);
  const visibility = { tongueHealth: !hidden, memberZone: hidden };
  const outputDir = path.resolve(options.outputDir || path.join(sourceRoot, 'dist', 'miniprogram', version));
  assertSafeOutput(outputDir, sourceRoot);
  fs.rmSync(outputDir, { recursive: true, force: true });
  copyTree(sourceRoot, outputDir, visibility, sourceRoot);
  rewriteAppJson(outputDir, hidden);
  writeBuildPolicy(outputDir, createBuildPolicy(version, hidden));
  writePackageJson(outputDir, version);
  validateOutput(outputDir, visibility);
  if (!options.quiet) {
    console.log(`[miniprogram] version=${version} tongueHealth=${!hidden} output=${outputDir}`);
  }
  return { outputDir, hidden, version };
}

function parseArgs(argv) {
  const options = {};
  for (let i = 0; i < argv.length; i += 1) {
    const item = argv[i];
    if (item === '--version') options.version = argv[++i];
    else if (item === '--output') options.outputDir = argv[++i];
    else if (item === '--config-url') options.configUrl = argv[++i];
    else if (item === '--policy-file') options.policyFile = argv[++i];
    else if (item === '--quiet') options.quiet = true;
  }
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const policy = options.policyFile
    ? JSON.parse(fs.readFileSync(path.resolve(options.policyFile), 'utf8'))
    : await fetchReleasePolicy(
        options.configUrl || process.env.MINI_CONFIG_BASE_URL || 'https://api.wulingshiguang.top'
      );
  await buildMiniProgram({
    version: options.version,
    outputDir: options.outputDir,
    policy,
    quiet: options.quiet
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(`[miniprogram] ${error.message}`);
    process.exitCode = 1;
  });
}

export { buildMiniProgram, createBuildPolicy, fetchReleasePolicy, processFeatureMarkers };
