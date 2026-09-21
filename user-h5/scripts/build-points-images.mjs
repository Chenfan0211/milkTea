import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const referenceRoot = path.join(root, 'design', 'reference');
const outputDir = path.join(root, 'assets', 'images', '3x');

const jobs = [
  {
    source: 'points/detail-pet.png',
    output: 'points-product-pet.jpg',
    crop: [0, 252, 1170, 1008],
    target: [750, 646],
    maxBytes: 80 * 1024
  },
  {
    source: 'points/detail-coupon.png',
    output: 'points-product-matcha.jpg',
    crop: [0, 252, 1170, 1008],
    target: [750, 646],
    maxBytes: 80 * 1024
  },
  {
    source: 'points/mall.jpg',
    output: 'points-product-single.jpg',
    crop: [64, 2000, 500, 420],
    target: [510, 456],
    maxBytes: 15 * 1024
  },
  {
    source: 'points/mall.jpg',
    output: 'points-product-half.jpg',
    crop: [620, 2000, 500, 420],
    target: [510, 456],
    maxBytes: 15 * 1024
  },
  {
    source: 'points/exchange-records.png',
    output: 'points-empty.jpg',
    crop: [260, 590, 650, 520],
    target: [650, 520],
    maxBytes: 15 * 1024
  },
  {
    source: 'points-signin/main-unsigned.jpg',
    output: 'points-signin-calendar.jpg',
    crop: [470, 390, 590, 340],
    target: [600, 400],
    maxBytes: 80 * 1024
  }
];

function psLiteral(value) {
  return `'${value.replaceAll("'", "''")}'`;
}

function cropJpeg(job, quality) {
  const source = path.join(referenceRoot, job.source);
  const output = path.join(outputDir, job.output);
  const [cropX, cropY, cropWidth, cropHeight] = job.crop;
  const [targetWidth, targetHeight] = job.target;
  const script = `
Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile(${psLiteral(source)})
try {
  $output = New-Object System.Drawing.Bitmap(${targetWidth}, ${targetHeight})
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($output)
    try {
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $destination = [System.Drawing.Rectangle]::new(0, 0, ${targetWidth}, ${targetHeight})
      $crop = [System.Drawing.Rectangle]::new(${cropX}, ${cropY}, ${cropWidth}, ${cropHeight})
      $graphics.DrawImage($source, $destination, $crop, [System.Drawing.GraphicsUnit]::Pixel)
    } finally {
      $graphics.Dispose()
    }
    $encoder = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
    if (-not $encoder) { throw '未找到 JPEG 编码器' }
    $parameters = New-Object System.Drawing.Imaging.EncoderParameters(1)
    $parameters.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]${quality})
    $output.Save(${psLiteral(output)}, $encoder, $parameters)
  } finally {
    $output.Dispose()
  }
} finally {
  $source.Dispose()
}
`;
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${job.output} 裁切失败\n${result.stderr.trim()}`);
}

fs.mkdirSync(outputDir, { recursive: true });

for (const job of jobs) {
  const source = path.join(referenceRoot, job.source);
  const output = path.join(outputDir, job.output);
  if (!fs.existsSync(source)) throw new Error(`缺少积分商城参考图: ${source}`);

  for (const quality of [88, 78, 68, 58, 48]) {
    cropJpeg(job, quality);
    if (fs.statSync(output).size <= job.maxBytes) break;
  }

  if (fs.statSync(output).size > job.maxBytes) {
    throw new Error(`${job.output} 压缩后仍超过 ${job.maxBytes} bytes`);
  }
  console.log(`积分素材生成: ${job.output}`);
}

console.log(`积分商城素材构建完成: ${jobs.length} 个输出`);
