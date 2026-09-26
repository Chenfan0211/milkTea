# =============================================================
# 重新安装「公共模块」到本地仓库
#
# 为什么需要这个脚本（踩坑固化）：
#   wuling-common / security-common 是其它模块的编译期依赖，
#   Maven 会从本地仓库读它们的 jar。改了公共模块却只做
#   `mvn -pl trade-service compile` 时，用的仍是旧 jar，
#   报错会指向业务代码（例如「找不到合适的构造器」），
#   而病根在构建产物 —— 排查成本极高。
#
#   额外坑：模块目录名是 wuling-common，但 artifactId 是 common，
#   所以在 .m2 里找的是 com/wuling/common/，容易误判为「没装过」。
#
# 用法：
#   pwsh -File scripts/install-common.ps1
#
# 改动公共模块后跑一次，再编译其它服务即可。
#
# -------------------------------------------------------------
# 附：另一个同类坑 ——「反向验证后必须 clean」
#
# 现象：做回归验证时把代码临时改回旧实现，跑测试（如期失败），
#       然后把源码还原，再跑测试 —— 依然失败，看起来像修复没生效。
#
# 原因：Maven 增量编译按「源文件时间戳」判断是否重编，
#       快速改回时时间戳可能落在同一秒内，class 没被重编，
#       于是测试仍跑的是「改动后」的旧字节码。
#
# 结论：任何「改回去再验证」的操作之后，先 clean 再 test：
#       mvn -pl trade-service clean test
#       或者只删目标模块的 target/classes 下对应 class。
#
# 这与上面「jar 不更新」是同一类问题：源码是对的，构建产物是旧的。
# 排查这类问题优先怀疑构建产物，而不是先怀疑代码。
# -------------------------------------------------------------
# =============================================================
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$m2 = Join-Path $env:USERPROFILE '.m2\repository\com\wuling'
$targets = @(
    @{ Name = 'wuling-common';   Artifact = 'common';          Jar = Join-Path $m2 'common\0.1.0-SNAPSHOT\common-0.1.0-SNAPSHOT.jar' },
    @{ Name = 'security-common'; Artifact = 'security-common'; Jar = Join-Path $m2 'security-common\0.1.0-SNAPSHOT\security-common-0.1.0-SNAPSHOT.jar' }
)

# ---------- 1. 安装前先判定是否真的过期 ----------
# 只看 jar 存在与否不够：jar 可能很旧但依然存在，正是事故场景。
# 这里比较「模块源码最新修改时间」与「本地仓库 jar 时间」。
function Get-NewestSourceTime([string] $moduleDir) {
    $srcDir = Join-Path $root "$moduleDir\src"
    if (-not (Test-Path -LiteralPath $srcDir)) { return $null }
    $newest = Get-ChildItem -LiteralPath $srcDir -Recurse -File -Include '*.java', '*.xml', '*.yml', '*.properties' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    return $newest.LastWriteTime
}

$stale = @()
foreach ($t in $targets) {
    $srcTime = Get-NewestSourceTime $t.Name
    if (-not $srcTime) { continue }
    if (-not (Test-Path -LiteralPath $t.Jar)) {
        $stale += $t.Name
        Write-Host ("  {0,-16} 本地仓库无 jar，需安装" -f $t.Name) -ForegroundColor Yellow
        continue
    }
    $jarTime = (Get-Item -LiteralPath $t.Jar).LastWriteTime
    if ($srcTime -gt $jarTime) {
        $stale += $t.Name
        Write-Host ("  {0,-16} 源码({1:MM-dd HH:mm}) 新于 jar({2:MM-dd HH:mm})，需重装" -f `
            $t.Name, $srcTime, $jarTime) -ForegroundColor Yellow
    } else {
        Write-Host ("  {0,-16} 已是最新" -f $t.Name) -ForegroundColor DarkGray
    }
}

if ($stale.Count -eq 0) {
    Write-Host '公共模块已是最新，无需重装。' -ForegroundColor Green
    exit 0
}

# ---------- 2. 重装并复验 ----------
Write-Host ("重装: " + ($stale -join ', ')) -ForegroundColor Cyan
# 用 -pl <模块目录名> 而非 artifactId：Maven 的 -pl 认的是目录
$modules = ($stale | ForEach-Object { $_ }) -join ','
mvn -q -pl $modules -am install -DskipTests
if ($LASTEXITCODE -ne 0) { throw "安装公共模块失败（退出码 $LASTEXITCODE）" }

# ---------- 3. 复验：源码时间必须已早于新 jar ----------
foreach ($t in $targets) {
    if ($stale -notcontains $t.Name) { continue }
    $srcTime = Get-NewestSourceTime $t.Name
    $jarTime = (Get-Item -LiteralPath $t.Jar).LastWriteTime
    if ($srcTime -gt $jarTime) {
        throw "$($t.Name) 重装后仍比源码旧（源码 $srcTime / jar $jarTime），install 未真正生效"
    }
    Write-Host ("  {0,-16} 重装完成 -> jar {1:MM-dd HH:mm}" -f $t.Name, $jarTime) -ForegroundColor Green
}

Write-Host '公共模块已就绪，可编译其它服务。' -ForegroundColor Green