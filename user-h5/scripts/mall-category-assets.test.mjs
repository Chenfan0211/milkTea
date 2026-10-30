import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const images3x = path.join(root, 'assets', 'images', '3x');
const tempDir = path.join(root, 'assets', 'temp', 'mall-category');

// ----------------------------------------------------------------
// 商城分类专用素材
//
// 线上商城首页已改为文字 Tab，不再渲染这些图。素材仍保留在仓库，
// 按原 48rpx 圆形规格的 3x 输出为 144×144；源图保持方图，便于以后复用。
// ----------------------------------------------------------------
const CATEGORY_ASSETS = [
  { code: 'tea-goods', output: 'mall-category-tea-goods.jpg', source: 'tea-goods.jpg' },
  { code: 'living-goods', output: 'mall-category-living-goods.jpg', source: 'living-goods.jpg' },
  { code: 'gift-box', output: 'mall-category-gift-box.jpg', source: 'gift-box.jpg' }
];

const MAX_BYTES = 20 * 1024;

/** 读取 JPEG 尺寸（与 check-project.mjs 同口径，避免引入额外依赖）。 */
function jpegSize(file) {
  const buffer = fs.readFileSync(file);
  let offset = 2;
  while (offset < buffer.length) {
    if (buffer[offset] !== 0xff) break;
    const marker = buffer[offset + 1];
    const length = buffer.readUInt16BE(offset + 2);
    if (
      (marker >= 0xc0 && marker <= 0xc3) ||
      (marker >= 0xc5 && marker <= 0xc7) ||
      (marker >= 0xc9 && marker <= 0xcb) ||
      (marker >= 0xcd && marker <= 0xcf)
    ) {
      return { height: buffer.readUInt16BE(offset + 5), width: buffer.readUInt16BE(offset + 7) };
    }
    offset += 2 + length;
  }
  throw new Error(`无法读取 JPEG 尺寸: ${file}`);
}

for (const asset of CATEGORY_ASSETS) {
  const output = path.join(images3x, asset.output);
  assert.equal(fs.existsSync(output), true, `分类「${asset.code}」必须有专用 3x 素材：${asset.output}`);

  const size = jpegSize(output);
  assert.equal(size.width, 144, `${asset.output} 宽度必须为 144（48rpx × 3x），实际 ${size.width}`);
  assert.equal(size.height, 144, `${asset.output} 高度必须为 144（48rpx × 3x），实际 ${size.height}`);

  const bytes = fs.statSync(output).size;
  assert.ok(bytes <= MAX_BYTES, `${asset.output} 不得超过 20KB，实际 ${(bytes / 1024).toFixed(1)}KB`);

  // 源图留在 assets/temp（已被 packOptions.ignore 排除），便于后续重新生成。
  const source = path.join(tempDir, asset.source);
  assert.equal(
    fs.existsSync(source),
    true,
    `分类「${asset.code}」缺少可重生成的源图：assets/temp/mall-category/${asset.source}`
  );
  const sourceSize = jpegSize(source);
  assert.equal(
    sourceSize.width,
    sourceSize.height,
    `源图 ${asset.source} 必须为方图（圆形裁切下保证主体不变形），实际 ${sourceSize.width}x${sourceSize.height}`
  );
}

// 三个分类必须各不相同，避免又退化成「多个分类共用一张图」。
const outputs = CATEGORY_ASSETS.map(asset => asset.output);
assert.equal(new Set(outputs).size, 3, '三个分类必须各用独立素材，不得复用同一张图');

const checksums = new Set(
  CATEGORY_ASSETS.map(asset => fs.readFileSync(path.join(images3x, asset.output)).toString('base64'))
);
assert.equal(checksums.size, 3, '三个分类素材的内容必须互不相同');

// ----------------------------------------------------------------
// 构建与校验链路必须记住这三张图，否则重新构建时会被遗漏或悄悄失效
// ----------------------------------------------------------------
const buildScript = fs.readFileSync(path.join(root, 'scripts', 'build-images.mjs'), 'utf8');
const checkScript = fs.readFileSync(path.join(root, 'scripts', 'check-project.mjs'), 'utf8');
for (const asset of CATEGORY_ASSETS) {
  assert.match(buildScript, new RegExp(asset.output.replace(/\./g, '\\.')), `build:images 必须生成 ${asset.output}`);
  assert.match(
    checkScript,
    new RegExp(asset.output.replace(/\./g, '\\.')),
    `check 必须校验 ${asset.output} 的尺寸与体积`
  );
}

// 源图目录必须被排除出小程序包（assets/temp 已被 packOptions.ignore 覆盖）。
const projectConfig = fs.readFileSync(path.join(root, 'project.config.json'), 'utf8');
assert.match(projectConfig, /"value":\s*"assets\/temp"/, 'assets/temp 必须被 packOptions.ignore 排除出小程序包');

console.log('商城分类胶囊专用素材校验通过（3 张 144×144）');
