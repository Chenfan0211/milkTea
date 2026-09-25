#!/bin/bash
# =============================================================
# 五零时光 · 后端服务部署脚本（Docker 版）
#
# 用法：
#   ./deploy-backend.sh build     构建全部镜像
#   ./deploy-backend.sh up        启动全部服务（拉齐/更新）
#   ./deploy-backend.sh down      停止全部服务
#   ./deploy-backend.sh restart   重启全部服务
#   ./deploy-backend.sh status    查看状态与端口
#   ./deploy-backend.sh logs <svc>  查看某服务日志
#   ./deploy-backend.sh verify    冒烟验证
#   ./deploy-backend.sh sync-scripts  同步运维脚本到 /opt/wuling/scripts/
#
# 设计说明：
#   1. 幂等：可重复执行 build/up，不会产生重复容器；
#   2. 构建前会生成本地构建上下文（jar + 配置），不在镜像内跑 Maven；
#   3. 启动顺序由 healthcheck 控制，避免网关先于下游注册导致 503。
# =============================================================
set -euo pipefail

APP_ROOT=/opt/wuling/app
BUILD_ROOT=/opt/wuling/build
COMPOSE_FILE=$BUILD_ROOT/docker-compose.prod.yml
IMAGE_TAG="${IMAGE_TAG:-$(date +%Y%m%d-%H%M%S)}"

# 服务名 -> jar 相对路径（与 Maven 构建产物一致）
declare -A JARS=(
  [gateway]=target/gateway-0.1.0-SNAPSHOT.jar
  [auth-service]=auth-service/target/auth-service-0.1.0-SNAPSHOT.jar
  [file-service]=file-service/target/file-service-0.1.0-SNAPSHOT.jar
  [marketing-service]=marketing-service/target/marketing-service-0.1.0-SNAPSHOT.jar
  [trade-service]=trade-service/target/trade-service-0.1.0-SNAPSHOT.jar
  [user-service]=user-service/target/user-service-0.1.0-SNAPSHOT.jar
  [product-service]=product-service/target/product-service-0.1.0-SNAPSHOT.jar
  [server]=server/target/server-0.1.0-SNAPSHOT.jar
)

# 服务名 -> 端口（用于 healthcheck 与验证）
declare -A PORTS=(
  [gateway]=8080
  [auth-service]=8081
  [file-service]=8082
  [marketing-service]=8083
  [trade-service]=8084
  [user-service]=8085
  [product-service]=8091
  [server]=8090
)

SERVICES=(gateway auth-service file-service marketing-service trade-service user-service product-service server)

log()  { echo -e "\033[1;32m[deploy]\033[0m $*"; }
warn() { echo -e "\033[1;33m[warn]\033[0m $*"; }
err()  { echo -e "\033[1;31m[error]\033[0m $*" >&2; }

compose() { docker compose -f "$COMPOSE_FILE" --project-name wuling "$@"; }

# ---------- 前置检查 ----------
preflight() {
  log "前置检查"

  command -v docker >/dev/null || { err "docker 未安装"; exit 1; }
  docker compose version >/dev/null || { err "docker compose 插件缺失"; exit 1; }

  # 中间件网络必须存在（由中间件 compose 创建）
  if ! docker network inspect wuling-net >/dev/null 2>&1; then
    err "网络 wuling-net 不存在。请先接入中间件网络，例如："
    err "  docker network create wuling-net"
    err "  docker network connect wuling-net wuling-mysql   # 对每个中间件容器执行"
    exit 1
  fi

  # 中间件容器应已加入该网络
  for c in wuling-mysql wuling-redis wuling-rabbitmq wuling-nacos; do
    if docker inspect "$c" >/dev/null 2>&1; then
      if ! docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$c" | grep -q wuling-net; then
        warn "$c 尚未加入 wuling-net，正在接入…"
        docker network connect wuling-net "$c"
      fi
    else
      warn "中间件容器 $c 不存在（若已用其他方式部署请忽略）"
    fi
  done

  [ -f "$APP_ROOT/secrets.env" ] || { err "缺少 $APP_ROOT/secrets.env"; exit 1; }
  chmod 600 "$APP_ROOT/secrets.env"

  fix_logging
  sync_scripts
  log "前置检查通过"
}

# ---------- 构建镜像 ----------
build() {
  cd "$BUILD_ROOT"
  for svc in "${SERVICES[@]}"; do
    local jar="${JARS[$svc]}"
    if [ ! -f "$jar" ]; then
      err "缺少 jar：$BUILD_ROOT/$jar"
      err "请先在项目根执行：mvn -DskipTests package"
      exit 1
    fi
    log "构建镜像 wuling/$svc:$IMAGE_TAG"
    docker build \
      -f docker/Dockerfile \
      --build-arg SERVICE="$svc" \
      --build-arg APP_JAR="$jar" \
      --build-arg APP_VERSION="$IMAGE_TAG" \
      -t "wuling/$svc:$IMAGE_TAG" \
      -t "wuling/$svc:latest" \
      . >/dev/null
  done
  log "全部镜像构建完成（tag=$IMAGE_TAG）"
  echo "$IMAGE_TAG" > "$BUILD_ROOT/.last-image-tag"
}

# ---------- 启动 ----------
up() {
  cd "$BUILD_ROOT"
  export IMAGE_TAG="$(cat "$BUILD_ROOT/.last-image-tag" 2>/dev/null || echo local)"
  log "启动服务（镜像 tag=$IMAGE_TAG）"
  compose up -d --no-build
  wait_healthy
}

# ---------- 等待健康 ----------
wait_healthy() {
  log "等待服务健康（最多 180s）"
  local deadline=$((SECONDS + 180))
  while [ $SECONDS -lt $deadline ]; do
    local bad=0
    for svc in "${SERVICES[@]}"; do
      local cid; cid=$(compose ps -q "$svc" 2>/dev/null || true)
      [ -z "$cid" ] && { bad=1; continue; }
      local st; st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")
      [ "$st" != "healthy" ] && bad=1
    done
    [ $bad -eq 0 ] && { log "全部服务已健康"; return 0; }
    sleep 5
  done
  warn "部分服务未在预期时间内健康，请检查：$0 status"
  return 1
}

# ---------- 停止 ----------
down() {
  cd "$BUILD_ROOT"
  log "停止服务"
  compose down
}

# ---------- 状态 ----------
status() {
  compose ps
  echo
  log "端口监听（应仅 127.0.0.1）"
  for svc in "${SERVICES[@]}"; do
    local p="${PORTS[$svc]}"
    printf "  %-18s :%s  " "$svc" "$p"
    if curl -fsS -m 3 "http://127.0.0.1:$p/actuator/health" >/dev/null 2>&1; then
      echo -e "\033[1;32mUP\033[0m"
    else
      echo -e "\033[1;31mDOWN\033[0m"
    fi
  done
}

# ---------- 运维脚本同步 ----------
# 把项目 scripts/ 下的运维脚本（clean-logs.sh / alert-check.sh 等）
# 从 BUILD_ROOT/scripts/ 复制到 /opt/wuling/scripts/ 并赋可执行权限。
# 为什么单独一个命令：
#   运维脚本（日志清理、告警检查）与 jar/配置一样属于部署产物，
#   但服务器上 BUILD_ROOT 只放 compose/Dockerfile/jar，不含 scripts，
#   因此约定：scripts/ 随部署上传到 BUILD_ROOT/scripts/，本函数负责落位。
sync_scripts() {
  local SRC="$BUILD_ROOT/scripts"
  local DST="/opt/wuling/scripts"

  [ -d "$SRC" ] || { warn "未找到运维脚本目录 $SRC，跳过同步（不影响应用启动）"; return 0; }

  mkdir -p "$DST"
  local n=0
  for s in "$SRC"/*.sh; do
    [ -e "$s" ] || continue
    cp -f "$s" "$DST/" 2>/dev/null || { warn "复制失败：$s"; continue; }
    chmod +x "$DST/$(basename "$s")"
    n=$((n+1))
  done

  if [ "$n" -gt 0 ]; then
    log "已同步 $n 个运维脚本到 $DST"
    # 校验关键脚本已就位（缺了日志清理会导致日志无限增长）
    for need in clean-logs.sh alert-check.sh; do
      [ -f "$DST/$need" ] || warn "缺少运维脚本 $DST/$need（日志清理/告警检查可能未生效）"
    done
  else
    warn "运维脚本目录 $SRC 下未找到 *.sh，请确认已上传"
  fi
}

# ---------- 日志目录权限 ----------
# 为什么单独一个命令：
#   容器以非 root（uid 1000）运行，而 /opt/wuling/logs 原属主是 root。
#   权限不匹配时 logback 【不会报错】，只是静默不写文件 —— 排查成本极高，
#   因此这里显式处理，并在部署时主动校验。
fix_logging() {
  log "配置日志目录权限"

  mkdir -p /opt/wuling/logs

  # 授予容器内运行用户（uid 1000）写权限；保留读权限方便运维查看
  chown -R 1000:1000 /opt/wuling/logs
  chmod 755 /opt/wuling/logs

  # 已存在的历史日志文件属主是 root，需一并放开写权限，否则追加写同样失败
  find /opt/wuling/logs -maxdepth 1 -type f -name "*.log" -exec chmod 664 {} \; 2>/dev/null || true

  log "日志目录就绪：/opt/wuling/logs（属主 1000:1000）"
}

# 校验容器确实能写日志（避免「以为在写其实没写」）
check_logging() {
  log "校验日志可写性"
  local probe="wuling-log-probe"
  docker rm -f "$probe" >/dev/null 2>&1 || true

  local img; img=$(docker images --format '{{.Repository}}:{{.Tag}}' | grep '^wuling/file-service:' | grep -v latest | head -1)
  [ -z "$img" ] && img="wuling/file-service:latest"

  docker run --rm --name "$probe" \
    -v /opt/wuling/logs:/opt/wuling/logs \
    --entrypoint sh "$img" \
    -c "touch /opt/wuling/logs/.probe && echo WRITABLE && rm -f /opt/wuling/logs/.probe" 2>&1 | tail -1
}

# ---------- 冒烟验证 ----------
verify() {
  log "冒烟验证"
  local ok=0 fail=0
  check() {
    local name="$1" url="$2" expect="${3:-}"
    local out; out=$(curl -sS -m 8 "$url" 2>/dev/null || echo "__FAIL__")
    if [ "$out" = "__FAIL__" ]; then
      echo -e "  \033[1;31m✗\033[0m $name"; fail=$((fail+1)); return
    fi
    if [ -n "$expect" ] && ! echo "$out" | grep -q "$expect"; then
      echo -e "  \033[1;31m✗\033[0m $name （响应不含 $expect）"; fail=$((fail+1)); return
    fi
    echo -e "  \033[1;32m✓\033[0m $name"; ok=$((ok+1))
  }

  check "网关健康"      "http://127.0.0.1:8080/actuator/health" '"status":"UP"'
  check "门店列表"      "http://127.0.0.1:8080/api/v1/app/stores" '"code":0'
  check "菜单"          "http://127.0.0.1:8080/api/v1/app/menu" '"code":0'
  check "商品详情"      "http://127.0.0.1:8080/api/v1/app/products/classic-001" '"code":0'
  check "会员等级"      "http://127.0.0.1:8080/api/v1/app/member-levels" '"code":0'
  check "储值套餐"      "http://127.0.0.1:8080/api/v1/app/stored-value/packages" '"code":0'
  check "未登录被拦截"  "http://127.0.0.1:8080/api/v1/app/orders" '8888'

  echo
  log "通过 $ok 项，失败 $fail 项"
  [ $fail -eq 0 ] || return 1
}

case "${1:-}" in
  preflight) preflight ;;
  fix-logging) fix_logging; check_logging ;;
  check-logging) check_logging ;;
  build)     preflight; build ;;
  up)        preflight; up ;;
  down)      down ;;
  restart)   down; up ;;
  status)    status ;;
  verify)    verify ;;
  clean-logs) CLEAN_SCRIPT=/opt/wuling/scripts/clean-logs.sh
              [ -f "$CLEAN_SCRIPT" ] && bash "$CLEAN_SCRIPT" || { err "缺少 $CLEAN_SCRIPT（请先同步 scripts/ 到服务器）"; exit 1; } ;;
  sync-scripts) sync_scripts ;;
  install-log-cron) cat > /etc/cron.d/wuling-logs <<'EOF'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
10 3 * * * root /opt/wuling/scripts/clean-logs.sh >>/var/log/wuling-clean.log 2>&1
EOF
              chmod 644 /etc/cron.d/wuling-logs
              log "已安装日志清理 cron（每日 03:10，保留 90 天）" ;;
  logs)      shift; compose logs -f --tail=200 "${1:-gateway}" ;;
  *) cat <<USAGE
五零时光后端部署脚本（Docker）

  $0 preflight        环境检查、网络接入与日志目录授权
  $0 fix-logging      仅修复日志目录权限并校验可写
  $0 build            构建全部镜像
  $0 up               启动/更新全部服务
  $0 down             停止全部服务
  $0 restart          重启
  $0 status           状态与端口
  $0 verify           冒烟验证
  $0 logs <service>   跟踪日志
  $0 clean-logs       立即清理过期归档日志（保留 90 天）
  $0 sync-scripts     同步运维脚本到 /opt/wuling/scripts/
  $0 install-log-cron 安装每日日志清理 cron

示例：
  $0 preflight && $0 build && $0 up && $0 verify
USAGE
  ;;
esac
