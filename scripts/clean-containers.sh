#!/bin/bash
# =============================================================
# 五零时光 · 应用容器与旧镜像清理脚本
#
# 目标：每次更新后，只保留「最近 1 个版本」的应用镜像，清理其余产物。
#
# 背景：
#   1. 应用容器由 docker-compose.prod.yml 用固定 container_name 管理
#      （wuling-gateway 等），`compose up -d` 会替换同名容器，
#      因此「运行中残留旧容器」基本不会发生；真正堆积的是旧镜像。
#   2. deploy-backend.sh 每次 build 都会打新 tag（日期-时间）并覆盖 :latest，
#      不清理的话 wuling/*:<时间戳> 会无限累积，占满磁盘。
#
# 安全护栏（务必遵守，改动前请重新确认）：
#   - 只处理 wuling/ 前缀的**应用**镜像与 wuling- 前缀的应用容器；
#   - 中间件容器（wuling-mysql / wuling-redis / wuling-rabbitmq /
#     wuling-nacos）**永不触碰**；
#   - 绝不使用 --volumes（file-uploads 具名卷存的是用户上传文件，
#     删卷会导致图片永久丢失）；
#   - 不使用 docker system prune -a（会误删中间件/其他项目的镜像）；
#   - 正在运行的容器所用镜像永远保留（通过 docker ps 反查保护）。
#
# 保留规则（"最近 1 个版本"）：
#   - :latest 始终保留（回滚与 compose 默认引用都需要它）；
#   - 保留最新 1 个时间戳 tag；
#   - 其余更早的 wuling/*:<时间戳> 镜像删除。
#   可用 KEEP_VERSIONS 覆盖保留数量（默认 1）。
#
# 构建缓存（可选，默认开启但很保守）：
#   每次 docker build 都会累积 layer 缓存，长期可达数十 GB。
#   本脚本只会清理「超过 BUILD_CACHE_MAX_AGE 小时未访问」的缓存，
#   近期缓存保留以保证下次构建速度。默认 168 小时（7 天）。
#   设为 0 表示本次清理全部可回收缓存（仅在磁盘告急时使用）。
#
# 用法：
#   ./clean-containers.sh                 # 立即清理（保留最近 1 个版本）
#   DRY_RUN=true ./clean-containers.sh    # 只打印将删除的内容，不实际删除
#   KEEP_VERSIONS=3 ./clean-containers.sh # 保留最近 3 个版本
#   BUILD_CACHE_MAX_AGE=0 ./clean-containers.sh  # 清空全部构建缓存（磁盘告急时）
#
# 建议：接入部署流程（deploy-backend.sh up 成功后自动调用），或单独 cron。
# =============================================================
set -uo pipefail

KEEP_VERSIONS="${KEEP_VERSIONS:-1}"
DRY_RUN="${DRY_RUN:-false}"
# 构建缓存保留时长（小时）；0 = 全部可回收缓存都清掉
BUILD_CACHE_MAX_AGE="${BUILD_CACHE_MAX_AGE:-168}"
IMAGE_PREFIX="wuling/"
CONTAINER_PREFIX="wuling-"

# 中间件容器：任何情况下都不得删除
PROTECTED_CONTAINERS="wuling-mysql wuling-redis wuling-rabbitmq wuling-nacos wuling-clamav"

log()  { echo "[clean-containers] $*"; }
warn() { echo "[clean-containers][warn] $*"; }

# 校验保留数量为正整数
case "$KEEP_VERSIONS" in
  ''|*[!0-9]*) warn "非法 KEEP_VERSIONS：$KEEP_VERSIONS"; exit 2 ;;
esac

command -v docker >/dev/null 2>&1 || { warn "docker 未安装"; exit 1; }

log "开始清理（保留最近 ${KEEP_VERSIONS} 个版本，dry-run=${DRY_RUN}）"

# ---------- 1. 清理已退出的应用容器 ----------
# 只删「已退出」且名字以 wuling- 开头、且不在保护名单里的容器。
# 运行中的容器一律不动。
log "步骤 1/4：清理已退出的应用容器"
EXITED_IDS=$(docker ps -aq --filter "status=exited" --filter "name=^/${CONTAINER_PREFIX}" 2>/dev/null || true)
if [ -z "$EXITED_IDS" ]; then
  log "  无已退出的应用容器"
else
  for cid in $EXITED_IDS; do
    cname=$(docker inspect -f '{{.Name}}' "$cid" 2>/dev/null | sed 's#^/##')
    if echo " $PROTECTED_CONTAINERS " | grep -q " $cname "; then
      warn "  跳过受保护容器：$cname"
      continue
    fi
    if [ "$DRY_RUN" = "true" ]; then
      echo "  [dry-run] 将删除容器: $cname ($cid)"
    else
      docker rm "$cid" >/dev/null 2>&1 && echo "  删除容器: $cname" || warn "  删除失败: $cname"
    fi
  done
fi

# ---------- 2. 收集受保护镜像（运行中容器 + :latest） ----------
log "步骤 2/4：收集必须保留的镜像"

# 用「镜像 ID」做保护判定最可靠：
#   容器可能以 ID 或 tag 启动，靠 tag 字符串匹配会漏保护正在运行的镜像。
# 因此先把运行中容器所用镜像的 ID 全部收集起来，删除前逐一比对。
PROTECTED_IMAGE_IDS=""

# 2a. 所有运行中容器（含中间件）使用的镜像 ID
RUNNING_IMAGE_IDS=$(docker ps -q 2>/dev/null \
  | xargs -r docker inspect -f '{{.Image}}' 2>/dev/null || true)
PROTECTED_IMAGE_IDS="$RUNNING_IMAGE_IDS"

# 2b. wuling/*:latest 对应镜像 ID 永远保留（部署默认引用与回滚兜底）
LATEST_IMAGE_IDS=$(docker images --format '{{.ID}} {{.Repository}}:{{.Tag}}' 2>/dev/null \
  | grep "${IMAGE_PREFIX}" | grep ':latest$' | awk '{print $1}' || true)
PROTECTED_IMAGE_IDS="$PROTECTED_IMAGE_IDS
$LATEST_IMAGE_IDS"

# 去空行并去重，便于 grep 精确匹配
PROTECTED_IMAGE_IDS=$(echo "$PROTECTED_IMAGE_IDS" | sed '/^$/d' | sort -u)

# 传入镜像 ID（可能带 sha256: 前缀 / 完整 sha / 短 ID），判断是否受保护。
#
# 实现要点（原实现有 bug）：不要在 `while` 管道子 shell 里读取变量做比对，
# 也不要依赖 `local`（子 shell 中语义易错、且 grep -q 提前退出会与写管道竞争）。
# 这里改为：把两侧都归一化成 12 位短 ID 后，直接用 grep -Fx 精确匹配。
to_short_id() {
  local v="$1"
  v="${v#sha256:}"
  printf '%s' "${v:0:12}"
}

is_protected_id() {
  local id="$1"
  [ -z "$id" ] && return 1
  local short; short=$(to_short_id "$id")
  [ -z "$short" ] && return 1

  local pid
  for pid in $PROTECTED_IMAGE_IDS; do
    if [ "$(to_short_id "$pid")" = "$short" ]; then
      return 0
    fi
  done
  return 1
}

# ---------- 3. 只保留最近 N 个版本，删除更早的时间戳镜像 ----------
# 按每个服务分别判断：wuling/gateway:20260927-120000 这类 tag 按字典序
# 等价于按时间序（tag 是 yyyyMMdd-HHmmss），倒序即最新在前。
log "步骤 3/4：清理旧版本镜像（每个服务保留最近 ${KEEP_VERSIONS} 个时间戳 tag）"

REPOS=$(docker images --format '{{.Repository}}' 2>/dev/null \
  | grep "^${IMAGE_PREFIX}" | sort -u || true)

if [ -z "$REPOS" ]; then
  log "  未找到 wuling/ 应用镜像"
else
  for repo in $REPOS; do
    # 只把「标准时间戳 tag」（YYYYMMDD-HHMMSS）纳入版本管理。
    # 其余命名（如手工构建的 :v55-balpay）不参与排序——它们不是发版产物，
    # 若混入排序会因字典序（v > 数字）被错误当成"最新"而保留下发版镜像。
    # 这类 tag 一律只提示、不自动删除，避免误删人工保留的调试镜像。
    ENTRIES=$(docker images --format '{{.ID}} {{.Repository}}:{{.Tag}}' "$repo" 2>/dev/null \
      | grep -v ':latest$' \
      | grep -E ':[0-9]{8}-[0-9]{6}$' \
      | sort -k2 -r || true)

    OTHER_TAGS=$(docker images --format '{{.Repository}}:{{.Tag}}' "$repo" 2>/dev/null \
      | grep -v ':latest$' \
      | grep -Ev ':[0-9]{8}-[0-9]{6}$' || true)
    if [ -n "$OTHER_TAGS" ]; then
      for ot in $OTHER_TAGS; do
        log "  跳过非发版 tag（请人工确认）: $ot"
      done
    fi

    [ -z "$ENTRIES" ] && continue

    kept=0
    while IFS= read -r line; do
      [ -z "$line" ] && continue
      img_id=$(echo "$line" | awk '{print $1}')
      tag=$(echo "$line" | awk '{print $2}')

      # 运行中容器或 :latest 指向的镜像：永远保留，且不占用「历史版本」名额
      if is_protected_id "$img_id"; then
        log "  保护（使用中/最新）: $tag"
        continue
      fi

      kept=$((kept+1))
      if [ "$kept" -le "$KEEP_VERSIONS" ]; then
        log "  保留: $tag"
        continue
      fi

      if [ "$DRY_RUN" = "true" ]; then
        echo "  [dry-run] 将删除镜像: $tag"
      else
        docker rmi "$tag" >/dev/null 2>&1 \
          && echo "  删除镜像: $tag" \
          || warn "  删除失败（可能仍被其他 tag/容器引用）: $tag"
      fi
    done <<< "$ENTRIES"
  done
fi

# ---------- 4. 清理过期的构建缓存（保守） ----------
# 每次 docker build 都会累积 layer 缓存，长期可达数十 GB。
# 这里默认只清「超过 BUILD_CACHE_MAX_AGE 小时未访问」的部分，
# 保留近期缓存，避免下次发版构建变慢。
# 注意：不使用 docker system prune -a / --volumes，避免误删中间件资源与数据卷。
if [ "$BUILD_CACHE_MAX_AGE" = "0" ]; then
  log "步骤 4/4：清理全部可回收构建缓存（BUILD_CACHE_MAX_AGE=0）"
  if [ "$DRY_RUN" = "true" ]; then
    echo "  [dry-run] 将执行: docker builder prune -af"
  else
    docker builder prune -af || warn "构建缓存清理失败（不影响服务运行）"
  fi
else
  log "步骤 4/4：清理超过 ${BUILD_CACHE_MAX_AGE} 小时的构建缓存"
  if [ "$DRY_RUN" = "true" ]; then
    echo "  [dry-run] 将执行: docker builder prune -f --filter until=${BUILD_CACHE_MAX_AGE}h"
  else
    docker builder prune -f --filter "until=${BUILD_CACHE_MAX_AGE}h" \
      || warn "构建缓存清理失败（不影响服务运行）"
  fi
fi

# ---------- 汇总 ----------
echo
log "清理完成。当前 wuling/ 镜像："
docker images --format '  {{.Repository}}:{{.Tag}}\t{{.Size}}' 2>/dev/null \
  | grep "^  ${IMAGE_PREFIX}" || true

if command -v df >/dev/null 2>&1; then
  df -h /var/lib/docker 2>/dev/null | sed 's/^/  /' || true
fi
