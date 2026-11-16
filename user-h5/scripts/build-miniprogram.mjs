import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

const require = createRequire(import.meta.url);
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
const TEXT_EXTENSIONS = new Set(['.js', '.json', '.wxml', '.wxss']);

function toPosix(value) {
  return String(value || '').replaceAll('\\', '/');
}

function isSkipped(relativePath) {
  const normalized = toPosix(relativePath).replace(/^\/+|\/+$/g, '');
  if (!normalized) return false;
  const segments = normalized.split('/');
  if (segments.some(segment => ALWAYS_SKIP.has(segment))) return true;
  // ALWAYS_SKIP 里也可能登记多段路径（如 assets/temp）。
  // 只按单段比较会永远匹配不到，导致源图被整体复制进产物目录
  // （历史问题：dist 产物长期带着 assets/temp 的全部源图）。
  if ([...ALWAYS_SKIP].some(item => normalized === item || normalized.startsWith(item + '/'))) return true;
  return false;
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
  fs.mkdirSync(outputDir, { recursive: true });

  for (const entry of fs.readdirSync(sourceDir, { withFileTypes: true })) {
    const sourcePath = path.join(sourceDir, entry.name);
    const relativePath = path.relative(rootDir, sourcePath);
    if (isSkipped(relativePath)) continue;

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

function createBuildPolicy(version) {
  const featureTab = {
    pagePath: '/pages/feature-tab/feature-tab',
    text: '会员专区',
    icon: '/assets/icons/lucide/member.svg',
    activeIcon: '/assets/icons/lucide/member-active.svg'
  };

  return {
    version,
    features: {
      tongueHealth: true,
      memberZone: true
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
      featureTab,
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

function rewriteAppJson(outputDir) {
  const appJsonPath = path.join(outputDir, 'app.json');
  const appJson = JSON.parse(fs.readFileSync(appJsonPath, 'utf8'));
  appJson.tabBar.list = appJson.tabBar.list.map(item => {
    if (item.pagePath !== 'pages/feature-tab/feature-tab') return item;
    return {
      pagePath: 'pages/feature-tab/feature-tab',
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

function validateOutput(outputDir) {
  const appJson = JSON.parse(fs.readFileSync(path.join(outputDir, 'app.json'), 'utf8'));
  const thirdTab = appJson.tabBar.list[2];
  if (!thirdTab || thirdTab.pagePath !== 'pages/feature-tab/feature-tab') {
    throw new Error('第三个 Tab 必须固定指向动态宿主页');
  }
  if (!appJson.pages.includes('pages/tongue/tongue')) {
    throw new Error('运行时可切换产物必须保留舌诊页面');
  }
  if (!(appJson.subpackages || []).some(item => item.root === 'packageHealth')) {
    throw new Error('运行时可切换产物必须保留 packageHealth 分包');
  }

  for (const file of walkTextFiles(outputDir)) {
    const content = fs.readFileSync(file, 'utf8');
    if (content.includes('@mini-release:')) throw new Error(`构建产物残留功能标记: ${path.relative(outputDir, file)}`);
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
  const visibility = { tongueHealth: true, memberZone: true };
  const outputDir = path.resolve(options.outputDir || path.join(sourceRoot, 'dist', 'miniprogram', version));
  assertSafeOutput(outputDir, sourceRoot);
  fs.rmSync(outputDir, { recursive: true, force: true });
  copyTree(sourceRoot, outputDir, visibility, sourceRoot);
  rewriteAppJson(outputDir);
  writeBuildPolicy(outputDir, createBuildPolicy(version));
  writePackageJson(outputDir, version);
  validateOutput(outputDir);
  if (!options.quiet) {
    console.log(`[miniprogram] version=${version} runtimeTabBar=db output=${outputDir}`);
  }
  return { outputDir, hidden: false, version };
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
  await buildMiniProgram({
    version: options.version,
    outputDir: options.outputDir,
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
