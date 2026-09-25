import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appJson = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));

/**
 * 地理位置权限声明校验。
 *
 * 背景（真实故障）：调用 wx.getLocation 等接口时，若 app.json 缺少
 * `permission.scope.userLocation`，微信会直接弹「需要在 app.json 中声明
 * permission scope.userLocation 字段」，定位能力完全不可用。
 *
 * 另外自 2022 年起微信新规要求：凡使用地理位置类接口，必须同时在
 * `requiredPrivateInfos` 中登记，否则即使 scope 授权通过也会调用失败。
 * 两者缺一不可，因此这里一并锁定。
 */

// 1. 必须声明 scope.userLocation 且 desc 为非空业务说明
assert.ok(appJson.permission, 'app.json 必须声明 permission 字段');
assert.ok(
  appJson.permission['scope.userLocation'],
  'app.json 必须声明 permission.scope.userLocation'
);
const desc = appJson.permission['scope.userLocation'].desc;
assert.ok(
  typeof desc === 'string' && desc.trim().length >= 6,
  'scope.userLocation.desc 必须是可读的用途说明（会展示给用户）'
);
assert.ok(
  desc.includes('门店') || desc.includes('距离'),
  'scope.userLocation.desc 必须说明用于门店/距离等真实业务场景'
);

// 2. requiredPrivateInfos 必须存在且覆盖实际用到的接口
assert.ok(
  Array.isArray(appJson.requiredPrivateInfos) && appJson.requiredPrivateInfos.length > 0,
  'app.json 必须声明 requiredPrivateInfos（微信新规：地理位置接口需登记）'
);
assert.ok(
  appJson.requiredPrivateInfos.includes('getLocation'),
  'requiredPrivateInfos 必须包含 getLocation'
);

// 微信互斥规则：getFuzzyLocation（模糊定位）与 getLocation（精确位置）不可同时登记，
// 同时登记会导致编译期报错「is mutually exclusive with」，小程序直接无法启动。
// 本项目需要精确坐标做距离排序，故只登记 getLocation，禁止再出现 getFuzzyLocation。
assert.ok(
  !appJson.requiredPrivateInfos.includes('getFuzzyLocation'),
  'requiredPrivateInfos 不得同时登记 getFuzzyLocation（与 getLocation 互斥，会导致编译报错）'
);
const MUTUALLY_EXCLUSIVE = [
  ['getLocation', 'getFuzzyLocation']
];
for (const [left, right] of MUTUALLY_EXCLUSIVE) {
  const bothPresent =
    appJson.requiredPrivateInfos.includes(left) && appJson.requiredPrivateInfos.includes(right);
  assert.ok(
    !bothPresent,
    `requiredPrivateInfos 不得同时登记互斥接口 ${left} / ${right}`
  );
}
// 未开通的接口不得登记，避免审核风险
for (const api of ['chooseLocation', 'chooseAddress', 'choosePoi', 'startLocationUpdate', 'onLocationChange']) {
  if (appJson.requiredPrivateInfos.includes(api)) {
    // 登记了就必须确认确实在用（这里只做提示性校验：登记项必须能在代码中搜到调用）
    const files = fs
      .readdirSync(path.join(root, 'pages'), { withFileTypes: true })
      .filter(item => item.isDirectory())
      .map(item => path.join(root, 'pages', item.name));
    const used = files.some(dir => {
      return fs
        .readdirSync(dir)
        .filter(name => name.endsWith('.js'))
        .some(name => fs.readFileSync(path.join(dir, name), 'utf8').includes(`wx.${api}`));
    });
    assert.ok(
      used,
      `requiredPrivateInfos 登记了 ${api}，但代码中未找到 wx.${api} 调用，属于过度声明，应移除`
    );
  }
}

// 3. 代码中若出现需授权的定位接口，必须确保声明已覆盖（漏登记会调用失败）
const pageDirs = fs
  .readdirSync(path.join(root, 'pages'), { withFileTypes: true })
  .filter(item => item.isDirectory())
  .map(item => path.join(root, 'pages', item.name));
const utilsDir = path.join(root, 'utils');
const sources = [
  ...pageDirs.flatMap(dir =>
    fs.readdirSync(dir).filter(name => name.endsWith('.js')).map(name => fs.readFileSync(path.join(dir, name), 'utf8'))
  ),
  ...fs.readdirSync(utilsDir).filter(name => name.endsWith('.js')).map(name => fs.readFileSync(path.join(utilsDir, name), 'utf8')),
  fs.readFileSync(path.join(root, 'app.js'), 'utf8')
].join('\n');

const NEEDS_SCOPE = ['wx.getLocation', 'wx.chooseLocation', 'wx.getFuzzyLocation'];
const actuallyUsed = NEEDS_SCOPE.filter(api => sources.includes(api));
for (const api of actuallyUsed) {
  const shortName = api.replace('wx.', '');
  assert.ok(
    appJson.requiredPrivateInfos.includes(shortName),
    `代码使用了 ${api}，requiredPrivateInfos 必须登记 ${shortName}`
  );
}

console.log(
  `地理位置权限声明校验通过（实际使用 ${actuallyUsed.length ? actuallyUsed.join(', ') : '无直接调用'}）`
);