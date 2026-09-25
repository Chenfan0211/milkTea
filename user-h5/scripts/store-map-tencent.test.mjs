import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// ---------- 1. 密钥绝不允许出现在小程序包内 ----------
//
// 腾讯位置服务的 Key/SK 是高权限凭证：一旦随构建产物下发，
// 任何人都能取出并冒用配额。密钥只存放在服务端 app_config.tencent_map_key，
// 由 GeoCodeService 带 SN 签名调用，小程序端只传坐标。
const MINIPROGRAM_DIRS = ['pages', 'components', 'utils', 'data', 'scripts', 'custom-tab-bar'];
const SECRET_PATTERNS = [
  /DVIBZ-[A-Z0-9-]{10,}/,          // 腾讯地图 Key 形态
  /tPEAfvpk[A-Za-z0-9]{5,}/,       // 已泄露的 SK（防止回填）
  /TENCENT_MAP_KEY\s*[:=]\s*['"][^'"]+['"]/,
  /TENCENT_MAP_SK\s*[:=]\s*['"][^'"]+['"]/
];

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else out.push(full);
  }
  return out;
}

// 仅扫描文本类源文件；图片/字体等二进制不参与
const TEXT_EXT = new Set(['.js', '.json', '.wxml', '.wxss', '.mjs', '.cjs', '.ts']);
const files = [];
for (const dir of MINIPROGRAM_DIRS) {
  const full = path.join(root, dir);
  if (fs.existsSync(full)) files.push(...walk(full));
}
for (const name of ['app.js', 'app.json', 'app.wxss', 'config.js', 'package.json']) {
  const full = path.join(root, name);
  if (fs.existsSync(full)) files.push(full);
}

for (const file of files) {
  if (!TEXT_EXT.has(path.extname(file))) continue;
  const text = fs.readFileSync(file, 'utf8');
  for (const pattern of SECRET_PATTERNS) {
    assert.ok(
      !pattern.test(text),
      `小程序包内不得出现腾讯地图密钥: ${path.relative(root, file)}`
    );
  }
}

// ---------- 2. 小程序端必须通过自己的后端代理拿地理数据 ----------
const apiJs = fs.readFileSync(path.join(root, 'utils/api.js'), 'utf8');
assert.ok(
  apiJs.includes('/api/v1/app/geo/regeo'),
  '小程序端必须通过后端代理做逆地址解析，不得直连腾讯接口'
);
assert.ok(
  apiJs.includes('/api/v1/app/geo/distance'),
  '小程序端必须通过后端代理取真实距离，不得直连腾讯接口'
);
assert.ok(
  !/apis\.map\.qq\.com/.test(apiJs),
  '小程序端不得直连腾讯位置服务域名（密钥会随包下发）'
);

// ---------- 3. 真实距离装饰器：有数据用服务端值，无数据回落直线估算 ----------
const store = require(path.join(root, 'utils/store.js'));
const origin = { latitude: 28.1, longitude: 112.9 };
const storeList = [
  { id: 'a', latitude: 28.2, longitude: 113.0 },
  { id: 'b', latitude: 28.3, longitude: 113.1 }
];

const withReal = store.decorateStoresWithRealDistance(storeList, origin, [
  { index: 0, distanceKm: 5.432, durationMinutes: 14.2 },
  { index: 1, distanceKm: 9.1, durationMinutes: 22 }
]);
assert.equal(withReal[0].distanceLabel, '驾车5.43km', '有服务端距离时必须展示驾车距离');
assert.equal(withReal[0].distanceSource, 'server', '必须标记距离来源为服务端');
assert.equal(withReal[0].durationLabel, '约14分钟', '必须展示预估耗时');

const fallback = store.decorateStoresWithRealDistance(storeList, origin, []);
assert.ok(
  fallback[0].distanceLabel.startsWith('直线'),
  '服务端距离缺失（未配密钥/失败）时必须回落直线估算，保证列表可用'
);
assert.equal(fallback[0].distanceSource, undefined, '回落时不得标记为服务端距离');

console.log('门店地图腾讯位置服务接入与密钥边界测试通过');
