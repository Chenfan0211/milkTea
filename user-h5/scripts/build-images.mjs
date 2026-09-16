import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const sourceDir = path.join(root, 'assets', 'temp')
const outputDir = path.join(root, 'assets', 'images', '3x')

const jobs = [
  { source: 'home-hero.jpg', output: 'home-hero.jpg', width: 2250, height: 3120 },
  { source: 'join-banner.jpg', output: 'join-banner.jpg', width: 2130, height: 600, maxBytes: 420 * 1024 },
  { source: 'menu-banner.jpg', output: 'menu-banner.jpg', width: 1605, height: 420 },
  { source: 'menu-product.jpg', output: 'menu-product.jpg', width: 510, height: 630 },
  { source: 'profile-avatar.jpg', output: 'profile-avatar.jpg', width: 336, height: 336 },
  { source: 'profile-banner.jpg', output: 'profile-banner.jpg', width: 2130, height: 480 },
  { source: 'profile-hero.jpg', output: 'profile-hero.jpg', width: 2250, height: 1311, maxBytes: 480 * 1024 }
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
function enforceMaxBytes(file, maxBytes) {
  if (!maxBytes || !fs.existsSync(file) || fs.statSync(file).size <= maxBytes) return
  for (const quality of [85, 75, 65, 55]) {
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

fs.mkdirSync(outputDir, { recursive: true })
const upscayl = findUpscayl()

for (const job of jobs) {
  const source = path.join(sourceDir, job.source)
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

  if (!upscayl) {
    throw new Error('未找到 Upscayl。请设置 UPSCAYL_BIN，或将便携版放入系统临时目录。')
  }

  console.log(`生成 3x 素材: ${path.relative(root, source)} -> ${path.relative(root, output)}`)
  runUpscayl(upscayl, source, output, job)
  enforceMaxBytes(output, job.maxBytes)
}

console.log(`高清素材构建完成: ${jobs.length} 个输出`)
