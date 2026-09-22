<#
.SYNOPSIS
  本地开发数据库一键搭建脚本（Windows / Docker Desktop）

.DESCRIPTION
  在本地起一个独立的 MySQL 8 容器（端口 13307，避开隧道占用的 13306），
  并导入从生产导出的结构 + 种子数据。

  这样本地开发与生产**彻底隔离**，从根本上消除
  「本地误迁移/误改生产数据」的风险（第 0 期曾发生该事故）。

.PARAMETER DumpDir
  导出的 SQL 文件所在目录（先在生产执行 scripts/export-dev-db.sh 生成）

.EXAMPLE
  .\scripts\setup-local-db.ps1 -DumpDir E:\backup\dev
#>
param(
  [Parameter(Mandatory = $true)][string]$DumpDir,
  [string]$ContainerName = 'wuling-mysql-dev',
  [int]$Port = 13307,
  [string]$Password = 'wuling_dev_123'
)

$ErrorActionPreference = 'Stop'

Write-Host '=== 1. 检查 Docker ===' -ForegroundColor Cyan
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  Write-Host '未找到 docker 命令。请先安装 Docker Desktop 并启动。' -ForegroundColor Red
  Write-Host '下载：https://www.docker.com/products/docker-desktop/' -ForegroundColor Yellow
  exit 1
}
docker info *> $null
if ($LASTEXITCODE -ne 0) { Write-Host 'Docker 未运行，请先启动 Docker Desktop。' -ForegroundColor Red; exit 1 }

Write-Host '=== 2. 清理同名旧容器（若存在）===' -ForegroundColor Cyan
$exist = docker ps -a --filter "name=^/$ContainerName$" --format '{{.Names}}'
if ($exist) {
  Write-Host "  移除旧容器 $ContainerName"
  docker rm -f $ContainerName | Out-Null
}

Write-Host '=== 3. 启动 MySQL 8 容器 ===' -ForegroundColor Cyan
docker run -d --name $ContainerName `
  -e "MYSQL_ROOT_PASSWORD=$Password" `
  -e 'MYSQL_DATABASE=wuling' `
  -e 'TZ=Asia/Shanghai' `
  -p "${Port}:3306" `
  mysql:8.0 `
  --character-set-server=utf8mb4 `
  --collation-server=utf8mb4_unicode_ci `
  --default-time-zone=+08:00 | Out-Null

Write-Host '  等待 MySQL 就绪...' -NoNewline
for ($i = 0; $i -lt 60; $i++) {
  docker exec $ContainerName mysqladmin ping -uroot -p$Password --silent *> $null
  if ($LASTEXITCODE -eq 0) { break }
  Start-Sleep -Seconds 2; Write-Host '.' -NoNewline
}
Write-Host ' 就绪'

Write-Host '=== 4. 导入结构 + 种子数据 ===' -ForegroundColor Cyan
$sqls = Get-ChildItem -Path $DumpDir -Filter '*.sql' | Sort-Object Name
if (-not $sqls) { Write-Host "目录 $DumpDir 下没有 .sql 文件" -ForegroundColor Red; exit 1 }

foreach ($sql in $sqls) {
  Write-Host "  导入 $($sql.Name)"
  Get-Content $sql.FullName -Raw | docker exec -i $ContainerName mysql -uroot -p$Password wuling
  if ($LASTEXITCODE -ne 0) { Write-Host "  导入失败：$($sql.Name)" -ForegroundColor Red; exit 1 }
}

Write-Host ''
Write-Host '=== 完成 ===' -ForegroundColor Green
Write-Host "本地库地址: 127.0.0.1:$Port   账号: root / $Password   库: wuling"
Write-Host ''
Write-Host '请更新各服务 application-dev.yml：' -ForegroundColor Yellow
Write-Host "  url: jdbc:mysql://127.0.0.1:$Port/wuling?..." 
Write-Host '  username: root'
Write-Host '  password: (上面的口令)'
Write-Host '  flyway.enabled: true   # 本地库可以开启迁移'