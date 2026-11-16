import assert from 'node:assert/strict';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(root, 'scripts/release-miniprogram.mjs');
const release = await import(pathToFileURL(modulePath).href);

assert.equal(release.validateReleaseVersion('1.3.5'), '1.3.5');
assert.throws(() => release.validateReleaseVersion('1.3/5'), /版本号/);
assert.throws(() => release.validateReleaseVersion(''), /版本号/);
assert.throws(
  () => release.createUploadInvocation({ cliPath: 'cli.bat', projectDir: 'x', version: '1.3.5', description: 'a&b' }),
  /描述/
);

const policy = {
  schemaVersion: 2,
  defaultThirdTab: 'memberZone',
  releases: {
    '1.3.5': 'memberZone',
    '1.3.6': 'tongueHealth'
  }
};
assert.deepEqual(release.resolveReleaseChoice(policy, '1.3.5'), {
  mapped: true,
  thirdTab: 'memberZone'
});
assert.deepEqual(release.resolveReleaseChoice(policy, '1.3.7'), {
  mapped: false,
  thirdTab: 'memberZone'
});

const invocation = release.createUploadInvocation({
  cliPath: 'C:\\WeChat\\cli.bat',
  projectDir: 'C:\\build\\1.3.5',
  version: '1.3.5',
  description: '舌诊、设备功能优化4'
});
assert.equal(invocation.command, 'C:\\WeChat\\cli.bat');
assert.deepEqual(invocation.args, [
  'upload',
  '--project',
  'C:\\build\\1.3.5',
  '--version',
  '1.3.5',
  '--desc',
  '舌诊、设备功能优化4'
]);

const calls = [];
const result = await release.runRelease({
  version: '1.3.5',
  description: '舌诊、设备功能优化4',
  dryRun: false,
  skipCheck: true,
  fetchPolicy: async () => policy,
  build: async options => {
    calls.push(['build', options.version, options.outputDir]);
    return { outputDir: options.outputDir };
  },
  upload: async command => calls.push(['upload', command.version]),
  logger: { log() {}, warn() {} }
});
assert.equal(result.thirdTab, 'memberZone');
assert.equal(result.mapped, true);
assert.equal(calls.length, 2);
assert.deepEqual(calls[0], ['build', '1.3.5', path.join(root, 'dist', 'miniprogram', '1.3.5')]);
assert.deepEqual(calls[1], ['upload', '1.3.5']);

const dryRunCalls = [];
const dryRun = await release.runRelease({
  version: '1.3.6',
  description: '舌诊切回',
  dryRun: true,
  skipCheck: true,
  fetchPolicy: async () => policy,
  build: async options => {
    dryRunCalls.push('build');
    return { outputDir: options.outputDir };
  },
  upload: async () => dryRunCalls.push('upload'),
  logger: { log() {}, warn() {} }
});
assert.equal(dryRun.uploaded, false);
assert.deepEqual(dryRunCalls, ['build']);

console.log('release-miniprogram.test.mjs: passed');
