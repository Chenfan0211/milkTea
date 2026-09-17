import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceDir = path.join(root, 'assets', 'temp')
const outputDir = path.join(root, 'assets', 'images', '3x')
const designReferenceDir = path.join(root, 'design', 'reference')
const generatedSourceDir = path.join(os.tmpdir(), 'milktea-image-build')

const jobs = [
  { source: 'home-hero.jpg', output: 'home-hero.jpg', width: 2250, height: 3120 },
  { source: 'home-hero.jpg', output: 'share-home.jpg', width: 640, height: 512, maxBytes: 80 * 1024, cover: true, skipUpscayl: true },
  { source: 'join-banner.jpg', output: 'join-banner.jpg', width: 2130, height: 600, maxBytes: 320 * 1024 },
  { source: 'menu-banner.jpg', output: 'menu-banner.jpg', width: 1605, height: 420 },
  { source: 'menu-product.jpg', output: 'menu-product.jpg', width: 510, height: 630, maxBytes: 80 * 1024, normalizeContent: { threshold: 220, widthRatio: 0.88, heightRatio: 0.90 } },
  { source: 'profile-avatar.jpg', output: 'profile-avatar.jpg', width: 336, height: 336 },
  { source: 'profile-banner.jpg', output: 'profile-banner.jpg', width: 2130, height: 480, maxBytes: 120 * 1024 },
  { source: 'profile-hero.jpg', output: 'profile-hero.jpg', width: 2250, height: 1311, maxBytes: 480 * 1024 },
  { source: 'stored-value/reference.png', output: 'stored-value-banner.jpg', width: 1053, height: 468, maxBytes: 120 * 1024, crop: { x: 24, y: 224, width: 702, height: 312 }, cleanLeft: { width: 256, startSampleX: 30, endSampleX: 251 } }
]

function findUpscayl() {
  const explicit = process.env.UPSCAYL_BIN
  if (explicit && fs.existsSync(explicit)) return explicit

  const tempRoot = process.env.TEMP || process.env.TMPDIR
  if (!tempRoot) return null
  const candidate = path.join(tempRoot, 'upscayl-cli', 'upscayl-bin-20251207-174704-windows', 'upscayl-bin.exe')
  return fs.existsSync(candidate) ? candidate : null
}

function getJpegSize(file) {
  const buffer = fs.readFileSync(file)
  let offset = 2
  while (offset < buffer.length) {
    if (buffer[offset] !== 0xFF) break
    const marker = buffer[offset + 1]
    const length = buffer.readUInt16BE(offset + 2)
    if (
      (marker >= 0xC0 && marker <= 0xC3) ||
      (marker >= 0xC5 && marker <= 0xC7) ||
      (marker >= 0xC9 && marker <= 0xCB) ||
      (marker >= 0xCD && marker <= 0xCF)
    ) {
      return {
        height: buffer.readUInt16BE(offset + 5),
        width: buffer.readUInt16BE(offset + 7)
      }
    }
    offset += 2 + length
  }
  throw new Error(`无法读取 JPEG 尺寸: ${file}`)
}

function matchesTarget(file, job, source = null) {
  if (!fs.existsSync(file)) return false
  if (source && fs.existsSync(source) && fs.statSync(file).mtimeMs < fs.statSync(source).mtimeMs) return false
  if (job.maxBytes && fs.statSync(file).size > job.maxBytes) return false
  try {
    const size = getJpegSize(file)
    return size.width === job.width && size.height === job.height
  } catch {
    return false
  }
}

function compressJpeg(file, quality) {
  const output = `${file}.${process.pid}.compressed.jpg`
  const sourceLiteral = file.replace(/'/g, "''")
  const outputLiteral = output.replace(/'/g, "''")
  const script = `
Add-Type -AssemblyName System.Drawing
$source = '${sourceLiteral}'
$output = '${outputLiteral}'
$quality = ${quality}
$image = [System.Drawing.Image]::FromFile($source)
try {
  $encoder = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
  if (-not $encoder) { throw '未找到 JPEG 编码器' }
  $parameters = New-Object System.Drawing.Imaging.EncoderParameters(1)
  $parameters.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter([System.Drawing.Imaging.Encoder]::Quality, [long]$quality)
  $image.Save($output, $encoder, $parameters)
} finally {
  $image.Dispose()
}
`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8' })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`JPEG 压缩失败: ${path.basename(file)}\n${result.stderr.trim()}`)
  fs.renameSync(output, file)
}
function cropImage(source, output, job) {
  const crop = job.crop
  const cleanLeft = job.cleanLeft
  const sourceLiteral = source.replace(/'/g, "''")
  const outputLiteral = output.replace(/'/g, "''")
  const cleanLeftScript = cleanLeft ? `
    for ($y = 0; $y -lt ${crop.height}; $y++) {
      $leftColor = $bitmap.GetPixel(${cleanLeft.startSampleX}, $y)
      $rightColor = $bitmap.GetPixel(${cleanLeft.endSampleX}, $y)
      for ($x = 0; $x -lt ${cleanLeft.width}; $x++) {
        $ratio = if (${cleanLeft.width} -gt 1) { $x / (${cleanLeft.width} - 1) } else { 0 }
        $red = [Math]::Round($leftColor.R + ($rightColor.R - $leftColor.R) * $ratio)
        $green = [Math]::Round($leftColor.G + ($rightColor.G - $leftColor.G) * $ratio)
        $blue = [Math]::Round($leftColor.B + ($rightColor.B - $leftColor.B) * $ratio)
        $bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $red, $green, $blue))
      }
    }
  ` : ''
  const script = `
Add-Type -AssemblyName System.Drawing
$source = '${sourceLiteral}'
$output = '${outputLiteral}'
$image = [System.Drawing.Image]::FromFile($source)
try {
  $bitmap = New-Object System.Drawing.Bitmap(${crop.width}, ${crop.height})
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $graphics.DrawImage($image, (New-Object System.Drawing.Rectangle(0, 0, ${crop.width}, ${crop.height})), (New-Object System.Drawing.Rectangle(${crop.x}, ${crop.y}, ${crop.width}, ${crop.height})), [System.Drawing.GraphicsUnit]::Pixel)
${cleanLeftScript}
    } finally {
      $graphics.Dispose()
    }
    $bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Jpeg)
  } finally {
    $bitmap.Dispose()
  }
} finally {
  $image.Dispose()
}
`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8' })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`参考图裁切失败: ${path.basename(source)}\n${result.stderr.trim()}`)
}
function resolveSource(job) {
  if (!job.crop) return path.join(sourceDir, job.source)
  const reference = path.join(designReferenceDir, job.source)
  fs.mkdirSync(generatedSourceDir, { recursive: true })
  const generated = path.join(generatedSourceDir, `${path.basename(job.source, path.extname(job.source))}.jpg`)
  if (!fs.existsSync(reference)) {
    if (fs.existsSync(generated)) return generated
    throw new Error(`缺少储值页参考图: ${path.relative(root, reference)}`)
  }
  if (!fs.existsSync(generated) || fs.statSync(generated).mtimeMs < fs.statSync(reference).mtimeMs) {
    cropImage(reference, generated, job)
  }
  return generated
}

function resizeImage(source, output, width, height, cover = false) {
  const sourceLiteral = source.replace(/'/g, "''")
  const outputLiteral = output.replace(/'/g, "''")
  const drawScript = cover
    ? `$targetRatio = ${width} / ${height}
      $sourceRatio = $image.Width / $image.Height
      if ($sourceRatio -gt $targetRatio) {
        $sourceHeight = $image.Height
        $sourceWidth = [int]($sourceHeight * $targetRatio)
        $sourceX = [int](($image.Width - $sourceWidth) / 2)
        $sourceY = 0
      } else {
        $sourceWidth = $image.Width
        $sourceHeight = [int]($sourceWidth / $targetRatio)
        $sourceX = 0
        $sourceY = [int](($image.Height - $sourceHeight) / 2)
      }
      $graphics.DrawImage($image, (New-Object System.Drawing.Rectangle(0, 0, ${width}, ${height})), (New-Object System.Drawing.Rectangle($sourceX, $sourceY, $sourceWidth, $sourceHeight)), [System.Drawing.GraphicsUnit]::Pixel)`
    : `$graphics.DrawImage($image, 0, 0, ${width}, ${height})`
  const script = `
Add-Type -AssemblyName System.Drawing
$source = '${sourceLiteral}'
$output = '${outputLiteral}'
$image = [System.Drawing.Image]::FromFile($source)
try {
  $bitmap = New-Object System.Drawing.Bitmap(${width}, ${height})
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      ${drawScript}
    } finally {
      $graphics.Dispose()
    }
    $bitmap.Save($output, [System.Drawing.Imaging.ImageFormat]::Jpeg)
  } finally {
    $bitmap.Dispose()
  }
} finally {
  $image.Dispose()
}
`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8' })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`图片缩放失败: ${path.basename(source)}\n${result.stderr.trim()}`)
}

function enforceMaxBytes(file, maxBytes) {
  if (!maxBytes || !fs.existsSync(file) || fs.statSync(file).size <= maxBytes) return
  for (const quality of [85, 75, 65, 55, 45, 35, 25, 15]) {
    compressJpeg(file, quality)
    if (fs.statSync(file).size <= maxBytes) return
  }
  throw new Error(`JPEG 压缩后仍超过限制: ${path.basename(file)} (${fs.statSync(file).size} bytes)`)
}

function runUpscayl(binary, source, output, job) {
  const modelDir = process.env.UPSCAYL_MODEL_DIR || path.join(path.dirname(path.dirname(binary)), 'models')
  const modelName = process.env.UPSCAYL_MODEL_NAME || 'upscayl-standard-4x'
  const result = spawnSync(binary, [
    '-i', source,
    '-o', output,
    '-m', modelDir,
    '-n', modelName,
    '-z', '4',
    '-r', `${job.width}x${job.height}`,
    '-f', 'jpg',
    '-c', '90'
  ], { encoding: 'utf8', stdio: 'inherit' })

  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`Upscayl 处理失败: ${path.basename(source)}`)
}

function normalizeContentImage(file, job) {
  if (!job.normalizeContent) return
  const options = job.normalizeContent
  const output = `${file}.${process.pid}.normalized.jpg`
  const sourceLiteral = file.replace(/'/g, "''")
  const outputLiteral = output.replace(/'/g, "''")
  const script = `
Add-Type -AssemblyName System.Drawing
$source = '${sourceLiteral}'
$output = '${outputLiteral}'
$bitmap = [System.Drawing.Bitmap]::FromFile($source)
try {
  $minX = $bitmap.Width
  $maxX = -1
  $minY = $bitmap.Height
  $maxY = -1
  for ($y = 0; $y -lt $bitmap.Height; $y++) {
    for ($x = 0; $x -lt $bitmap.Width; $x++) {
      $color = $bitmap.GetPixel($x, $y)
      if ($color.R -lt ${options.threshold} -or $color.G -lt ${options.threshold} -or $color.B -lt ${options.threshold}) {
        if ($x -lt $minX) { $minX = $x }
        if ($x -gt $maxX) { $maxX = $x }
        if ($y -lt $minY) { $minY = $y }
        if ($y -gt $maxY) { $maxY = $y }
      }
    }
  }
  if ($maxX -lt 0 -or $maxY -lt 0) { throw '未检测到商品主体' }

  $contentWidth = $maxX - $minX + 1
  $contentHeight = $maxY - $minY + 1
  $maxDrawWidth = ${job.width} * ${options.widthRatio}
  $maxDrawHeight = ${job.height} * ${options.heightRatio}
  $scale = [Math]::Min($maxDrawWidth / $contentWidth, $maxDrawHeight / $contentHeight)
  $drawWidth = [int][Math]::Round($contentWidth * $scale)
  $drawHeight = [int][Math]::Round($contentHeight * $scale)
  $drawX = [int][Math]::Round((${job.width} - $drawWidth) / 2)
  $drawY = [int][Math]::Round((${job.height} - $drawHeight) / 2)

  $canvas = New-Object System.Drawing.Bitmap(${job.width}, ${job.height})
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($canvas)
    try {
      $graphics.Clear([System.Drawing.Color]::White)
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
      $sourceRect = New-Object System.Drawing.Rectangle($minX, $minY, $contentWidth, $contentHeight)
      $targetRect = New-Object System.Drawing.Rectangle($drawX, $drawY, $drawWidth, $drawHeight)
      $graphics.DrawImage($bitmap, $targetRect, $sourceRect, [System.Drawing.GraphicsUnit]::Pixel)
    } finally {
      $graphics.Dispose()
    }
    $canvas.Save($output, [System.Drawing.Imaging.ImageFormat]::Jpeg)
  } finally {
    $canvas.Dispose()
  }
} finally {
  $bitmap.Dispose()
}
`
  const result = spawnSync('powershell.exe', ['-NoProfile', '-Command', script], { encoding: 'utf8' })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`商品图居中处理失败: ${path.basename(file)}\n${result.stderr.trim()}`)
  fs.renameSync(output, file)
}

fs.mkdirSync(outputDir, { recursive: true })
const upscayl = findUpscayl()

const requestedOutput = process.env.IMAGE_OUTPUT
const jobsToRun = requestedOutput ? jobs.filter(job => job.output === requestedOutput) : jobs

for (const job of jobsToRun) {
  const source = resolveSource(job)
  const output = path.join(outputDir, job.output)

  if (!fs.existsSync(source)) {
    if (matchesTarget(output, job)) {
      console.log(`复用已有 3x 素材: ${path.relative(root, output)}`)
      continue
    }
    throw new Error(`缺少超分输入且没有可复用产物: ${path.relative(root, source)}`)
  }

  if (matchesTarget(output, job, source) && process.env.FORCE_IMAGE_REBUILD !== '1') {
    console.log(`跳过已有 3x 素材: ${path.relative(root, output)}`)
    continue
  }

  console.log(`生成 3x 素材: ${path.relative(root, source)} -> ${path.relative(root, output)}`)
  if (upscayl && !job.skipUpscayl) {
    runUpscayl(upscayl, source, output, job)
  } else {
    resizeImage(source, output, job.width, job.height, Boolean(job.cover))
  }
  if (job.normalizeContent) normalizeContentImage(output, job)
  enforceMaxBytes(output, job.maxBytes)
}

console.log(`高清素材构建完成: ${jobsToRun.length} 个输出`)
