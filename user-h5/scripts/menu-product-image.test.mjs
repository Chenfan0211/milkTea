import assert from 'node:assert/strict';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const imagePath = path.join(root, 'assets/images/3x/menu-product.jpg');
const buildScriptPath = path.join(root, 'scripts/build-images.mjs');
const pageWxssPath = path.join(root, 'pages/coupon-products/coupon-products.wxss');

assert.ok(fs.existsSync(imagePath), '缺少共享商品图 menu-product.jpg');
const imageBytes = fs.statSync(imagePath).size;
assert.ok(imageBytes <= 80 * 1024, `共享商品图不得超过 80KB，当前 ${imageBytes} bytes`);

const buildSource = fs.readFileSync(buildScriptPath, 'utf8');
assert.ok(buildSource.includes('normalizeContent'), '构建脚本必须保留共享商品图内容标准化步骤');
assert.ok(buildSource.includes('IMAGE_OUTPUT'), '构建脚本必须支持仅重建指定图片，避免无关素材被重写');

const probeScript = `
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile('${imagePath.replace(/'/g, "''")}')
try {
  $minX = $bmp.Width
  $maxX = -1
  $minY = $bmp.Height
  $maxY = -1
  for ($y = 0; $y -lt $bmp.Height; $y++) {
    for ($x = 0; $x -lt $bmp.Width; $x++) {
      $color = $bmp.GetPixel($x, $y)
      if ($color.R -lt 220 -or $color.G -lt 220 -or $color.B -lt 220) {
        if ($x -lt $minX) { $minX = $x }
        if ($x -gt $maxX) { $maxX = $x }
        if ($y -lt $minY) { $minY = $y }
        if ($y -gt $maxY) { $maxY = $y }
      }
    }
  }
  if ($maxX -lt 0 -or $maxY -lt 0) { throw '未检测到商品主体' }
  $width = $maxX - $minX + 1
  $height = $maxY - $minY + 1
  $centerOffset = [Math]::Abs((($minX + $maxX) / 2) - (($bmp.Width - 1) / 2))
  $leftPadding = $minX
  $rightPadding = $bmp.Width - 1 - $maxX
  Write-Output "$($bmp.Width)|$($bmp.Height)|$centerOffset|$leftPadding|$rightPadding|$width|$height"
} finally {
  $bmp.Dispose()
}
`;
const probe = spawnSync('powershell.exe', ['-NoProfile', '-Command', probeScript], { encoding: 'utf8' });
assert.equal(probe.status, 0, probe.stderr || '共享商品图像素检测失败');
const [width, height, centerOffset, leftPadding, rightPadding, contentWidth, contentHeight] = probe.stdout
  .trim()
  .split('|')
  .map(Number);
assert.equal(width, 510, '共享商品图宽度必须保持 510px');
assert.equal(height, 630, '共享商品图高度必须保持 630px');
assert.ok(centerOffset <= 4, `商品主体水平中心偏差不得超过 4px，当前 ${centerOffset.toFixed(1)}px`);
assert.ok(
  Math.abs(leftPadding - rightPadding) <= 8,
  `商品主体左右留白必须基本一致，当前 ${leftPadding}/${rightPadding}px`
);
assert.ok(contentWidth >= 430, `商品主体宽度必须接近目标占比，当前 ${contentWidth}px`);
assert.ok(contentHeight >= 550, `商品主体高度必须接近目标占比，当前 ${contentHeight}px`);

const pageWxss = fs.readFileSync(pageWxssPath, 'utf8');
const mediaRule = pageWxss.match(/\.coupon-product__media\s*\{([\s\S]*?)\}/)?.[1] || '';
const imageRule = pageWxss.match(/\.coupon-product__image\s*\{([\s\S]*?)\}/)?.[1] || '';
assert.ok(
  /display:\s*flex/.test(mediaRule) &&
    /align-items:\s*center/.test(mediaRule) &&
    /justify-content:\s*center/.test(mediaRule),
  '适用商品图片容器必须水平垂直居中'
);
assert.ok(
  mediaRule.includes('height: 220rpx') && mediaRule.includes('background: var(--card-bg)'),
  '适用商品图片区必须使用 220rpx 白底画框'
);
assert.ok(
  /display:\s*block/.test(imageRule) && /width:\s*100%/.test(imageRule) && /height:\s*100%/.test(imageRule),
  '适用商品图片必须块级铺满并居中展示'
);

console.log('共享商品图居中与适用商品排版测试通过');
