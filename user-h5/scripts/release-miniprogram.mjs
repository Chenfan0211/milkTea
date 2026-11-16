import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { buildMiniProgram } from './build-miniprogram.mjs';

const require = createRequire(import.meta.url);
const tabBarConfig = require('../utils/tab-bar-config.js');
const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const sourceRoot = path.resolve(scriptDir, '..');
const VERSION_PATTERN = /^[A-Za-z0-9.]+$/;
const DEFAULT_CLI = 'C:\\Program Files (x86)\\Tencent\\微信web开发者工具\\cli.bat';
const CONFIG_URL = 'https://api.wulingshiguang.top/api/v1/app/config/mini_tab_bar';

function validateReleaseVersion(value) {
  const version = String(value || '').trim();
  if (!version || !VERSION_PATTERN.test(version)) {
    throw new Error(`版本号仅允许字母、数字和小数点: ${version || '(empty)'}`);
  }
  return version;
}

function requireDescription(value) {
  const description = String(value || '').trim();
  if (!description) throw new Error('缺少上传描述 --desc');
  if (/["&|<>^%!\r\n]/.test(description)) {
    throw new Error('上传描述包含不支持的 shell 特殊字符');
  }
  return description;
}

function resolveReleaseChoice(policyValue, versionValue) {
  const policy = tabBarConfig.normalize(policyValue);
  const version = validateReleaseVersion(versionValue);
  const mapped = Object.prototype.hasOwnProperty.call(policy.releases, version);
  return {
    mapped,
    thirdTab: mapped ? policy.releases[version] : policy.defaultThirdTab
  };
}

function normalizePolicyPayload(body) {
  let value = body;
  if (value && typeof value === 'object' && Object.prototype.hasOwnProperty.call(value, 'data')) {
    value = value.data;
  }
  if (typeof value === 'string') value = JSON.parse(value);
  return value;
}

async function fetchPolicy(url = CONFIG_URL, timeoutMs = 15000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) throw new Error(`读取导航配置失败: HTTP ${response.status}`);
    return normalizePolicyPayload(await response.json());
  } finally {
    clearTimeout(timer);
  }
}

function runCheck() {
  const result =
    process.platform === 'win32'
      ? spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', 'npm run check'], {
          cwd: sourceRoot,
          stdio: 'inherit',
          windowsVerbatimArguments: true
        })
      : spawnSync('npm', ['run', 'check'], {
          cwd: sourceRoot,
          stdio: 'inherit'
        });
  if (result.status !== 0) throw new Error(`发布前检查失败，exit=${result.status}`);
}

function createUploadInvocation({ cliPath, projectDir, version, description }) {
  return {
    command: cliPath || process.env.WECHAT_DEVTOOLS_CLI || DEFAULT_CLI,
    args: [
      'upload',
      '--project',
      projectDir,
      '--version',
      validateReleaseVersion(version),
      '--desc',
      requireDescription(description)
    ]
  };
}

function uploadMiniProgram(invocation) {
  let result;
  if (process.platform === 'win32' && /\.(bat|cmd)$/i.test(invocation.command)) {
    const quoted = invocation.args.map(arg => `"${String(arg).replaceAll('"', '""')}"`).join(' ');
    const command = `""${invocation.command}" ${quoted}"`;
    result = spawnSync(process.env.ComSpec || 'cmd.exe', ['/d', '/s', '/c', command], {
      cwd: sourceRoot,
      stdio: 'inherit',
      windowsVerbatimArguments: true
    });
  } else {
    result = spawnSync(invocation.command, invocation.args, {
      cwd: sourceRoot,
      stdio: 'inherit',
      shell: false
    });
  }
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`微信小程序上传失败，exit=${result.status}`);
}

async function runRelease(options = {}) {
  const version = validateReleaseVersion(options.version);
  const description = requireDescription(options.description);
  const logger = options.logger || console;
  const check = options.check || runCheck;
  const fetchReleasePolicy = options.fetchPolicy || fetchPolicy;
  const build = options.build || buildMiniProgram;
  const upload = options.upload || uploadMiniProgram;

  if (!options.skipCheck) await check();

  let policy;
  try {
    policy = await fetchReleasePolicy();
  } catch (error) {
    throw new Error(`无法读取数据库导航配置，已阻止上传: ${error.message}`);
  }
  const choice = resolveReleaseChoice(policy, version);
  if (!choice.mapped) {
    logger.warn(`[release] ${version} 未配置，将使用默认第三项 ${choice.thirdTab}`);
  } else {
    logger.log(`[release] ${version} -> ${choice.thirdTab}`);
  }

  const outputDir = path.join(sourceRoot, 'dist', 'miniprogram', version);
  await build({ sourceRoot, version, outputDir });
  const invocation = createUploadInvocation({
    cliPath: options.cliPath,
    projectDir: outputDir,
    version,
    description
  });

  if (options.dryRun) {
    logger.log(`[release] dry-run: ${invocation.command} ${invocation.args.join(' ')}`);
    return {
      uploaded: false,
      outputDir,
      version,
      ...choice
    };
  }

  await upload({ ...invocation, version, description });
  return {
    uploaded: true,
    outputDir,
    version,
    ...choice
  };
}

function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (item === '--version') options.version = argv[++index];
    else if (item === '--desc') options.description = argv[++index];
    else if (item === '--dry-run') options.dryRun = true;
    else if (item === '--skip-check') options.skipCheck = true;
    else if (item === '--cli') options.cliPath = argv[++index];
  }
  return options;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  await runRelease(options);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => {
    console.error(`[release] ${error.message}`);
    process.exitCode = 1;
  });
}

export {
  CONFIG_URL,
  DEFAULT_CLI,
  createUploadInvocation,
  fetchPolicy,
  parseArgs,
  resolveReleaseChoice,
  runRelease,
  validateReleaseVersion
};
