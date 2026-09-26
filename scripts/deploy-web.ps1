<#
.SYNOPSIS
  运营后台前端一键构建 + 打包 + 上传 + 部署脚本

.DESCRIPTION
  完成「构建 -> 打 tar.gz -> scp 上传 -> 服务器备份旧产物并部署 -> reload nginx」全流程。

  访问模式（-Mode 切换）：
    ip    : pnpm build:prod-ip，接口走 http://<服务器IP>:8089（当前过渡方案）
    https : pnpm build:prod，  接口走 https://api.wulingshiguang.top（证书就绪后）

.PARAMETER Mode
  构建模式，ip（默认）或 https。
.PARAMETER Server
  服务器地址，默认 43.136.91.239。
.PARAMETER SshUser
  SSH 用户，默认 root（需免密登录）。
.PARAMETER DeployDir
  服务器前端部署目录，默认 /opt/wuling/web。
.PARAMETER SkipUpload
  仅本地构建+打包，不上传。

.EXAMPLE
  .\scripts\deploy-web.ps1
  .\scripts\deploy-web.ps1 -Mode https
  .\scripts\deploy-web.ps1 -SkipUpload
#>
param(
  [ValidateSet('ip', 'https')][string]$Mode = 'ip',
  [string]$Server = '43.136.91.239',
  [string]$SshUser = 'root',
  [string]$DeployDir = '/opt/wuling/web',
  [switch]$SkipUpload
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Step([string]$msg) {
  Write-Host ("`n=== {0} ===" -f $msg) -ForegroundColor Cyan
}

# ---------- 1. 构建 ----------
Step "构建前端（Mode=$Mode）"
Push-Location $Root
try {
  if ($Mode -eq 'https') { pnpm build:prod } else { pnpm build:prod-ip }
  if ($LASTEXITCODE -ne 0) { throw "构建失败（exit=$LASTEXITCODE）" }
} finally { Pop-Location }

# ---------- 2. 校验产物基址 ----------
Step '校验产物基址'
$dist = Join-Path $Root 'dist'
if (-not (Test-Path $dist)) { throw '未找到 dist 目录' }

$httpsMark = 'api.wulingshiguang.top'
$ipMark = '43.136.91.239'
$hasHttps = $false; $hasIp = $false; $hasRemoteIconRuntime = $false
Get-ChildItem $dist -Recurse -File | ForEach-Object {
  $c = Get-Content $_.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
  if ($c -match [regex]::Escape($httpsMark)) { $hasHttps = $true }
  if ($c -match [regex]::Escape($ipMark)) { $hasIp = $true }
  if ($c -match 'https://api\.(?:iconify\.design|simplesvg\.com|unisvg\.com)') { $hasRemoteIconRuntime = $true }
}

if ($hasRemoteIconRuntime) {
  throw '产物中残留 Iconify 公网地址。请确认使用 @iconify/vue/offline，并执行 icons:offline 后再构建。'
}

if ($Mode -eq 'https') {
  if (-not $hasHttps) { Write-Warning '产物中未发现 HTTPS 域名，请确认 build:prod 生效' }
  if ($hasIp) { Write-Warning '产物中仍残留 IP 地址' }
} else {
  if ($hasHttps) { throw "产物中仍残留 HTTPS 域名（$httpsMark），请确认 build:prod-ip 生效" }
  if (-not $hasIp) { Write-Warning '产物中未发现 IP 地址，请确认 .env.prod-ip 配置' }
}
Write-Host "  基址校验通过（$Mode）" -ForegroundColor Green

# ---------- 3. 打包 ----------
Step '打包 tar.gz'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$pkgName = "web-$Mode-$stamp.tar.gz"
$pkgPath = Join-Path $Root $pkgName
Push-Location $dist
try {
  tar -czf $pkgPath .
  if ($LASTEXITCODE -ne 0) { throw 'tar 打包失败' }
} finally { Pop-Location }
Write-Host "  生成：$pkgPath" -ForegroundColor Green

if ($SkipUpload) {
  Step '跳过上传'
  Write-Host "  产物已就绪：$pkgPath" -ForegroundColor Yellow
  exit 0
}

# ---------- 4. 上传 ----------
Step "上传到 ${SshUser}@${Server}"
$remoteTmp = "/tmp/$pkgName"
scp -q $pkgPath "${SshUser}@${Server}:$remoteTmp"
if ($LASTEXITCODE -ne 0) { throw 'scp 上传失败' }
Write-Host "  已上传到 $remoteTmp" -ForegroundColor Green

# ---------- 5. 服务器端部署 ----------
Step '服务器端备份 + 部署 + reload'
$backupDir = '/opt/wuling/backup'
# 远程脚本：用占位符拼接，避免 here-string 嵌套冲突
$r = @(
'set -e'
'BACKUP_DIR=/opt/wuling/backup'
'DEPLOY_DIR={DEPLOY}'
'STAMP={STAMP}'
'PKG={PKG}'
'mkdir -p "$BACKUP_DIR"'
'if [ -d "$DEPLOY_DIR" ]; then'
'  OLD="$DEPLOY_DIR.old-$STAMP"'
'  tar -czf "$BACKUP_DIR/web-pre-$STAMP.tar.gz" -C "$DEPLOY_DIR" . 2>/dev/null || true'
'  mv "$DEPLOY_DIR" "$OLD"'
'  echo "backup -> $OLD"'
'fi'
'mkdir -p "$DEPLOY_DIR"'
'tar -xzf "/tmp/$PKG" -C "$DEPLOY_DIR"'
'rm -f "/tmp/$PKG"'
'systemctl reload nginx'
'echo "DEPLOY_OK $DEPLOY_DIR"'
)
$remoteScript = ($r -join "`n").Replace('{DEPLOY}', $DeployDir).Replace('{STAMP}', $stamp).Replace('{PKG}', $pkgName)
$remoteScript | ssh "${SshUser}@${Server}" 'bash -s'
if ($LASTEXITCODE -ne 0) { throw '服务器端部署失败' }

Step '完成'
Write-Host "  部署目录：$DeployDir" -ForegroundColor Green
Write-Host "  备份：$backupDir/web-pre-$stamp.tar.gz" -ForegroundColor Green
Write-Host "  回滚（服务器执行）：mv $DeployDir.old-$stamp $DeployDir ; systemctl reload nginx" -ForegroundColor Yellow
if ($Mode -eq 'https') {
  Write-Host '  访问入口：https://api.wulingshiguang.top' -ForegroundColor Green
} else {
  Write-Host "  访问入口：http://$Server/" -ForegroundColor Green
}
