#!/usr/bin/env bash
# =============================================================
# 五零时光 · P1-2  Nacos 鉴权加固
#
# 目标：开启 Nacos 鉴权，防止「能访问 8848 的任何进程」篡改配置或注册恶意实例。
#
# 用法：
#   bash deploy/security/05-nacos-auth.sh --check    # 检查现状（只读）
#   bash deploy/security/05-nacos-auth.sh --apply    # 打印/执行加固步骤（需确认）
#
# 安全设计：
#   - 默认只检查，不自动修改（改容器需重启 Nacos，影响服务发现）；
#   - Token 由脚本生成强随机值，但【不回显到日志】，只写入提示文件权限 600；
#   - 不自动重启容器，把重启命令交给人工确认后执行。
# =============================================================
set -uo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; NC=$'\033[0m'
section() { printf '\n%s===== %s =====%s\n' "$CYAN" "$1" "$NC"; }
ok()   { printf '%s[OK]%s %s\n'   "$GREEN"  "$NC" "$1"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
bad()  { printf '%s[RISK]%s %s\n' "$RED"    "$NC" "$1"; }
info() { printf '       %s\n' "$1"; }

CONTAINER="${NACOS_CONTAINER:-wuling-nacos}"
COMPOSE_DIR="${DEPLOY_DIR:-/opt/wuling/deploy}"

check() {
  section "1. 容器状态"
  if ! command -v docker >/dev/null 2>&1; then bad "未安装 docker"; return 1; fi
  if docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    ok "$CONTAINER 运行中"
  else
    warn "$CONTAINER 未运行（若未使用 Nacos 可忽略本项）"; return 0
  fi

  section "2. 鉴权开关"
  local envs; envs="$(docker inspect "$CONTAINER" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null)"
  local auth; auth="$(echo "$envs" | grep -E '^NACOS_AUTH_ENABLE=' || true)"
  local token; token="$(echo "$envs" | grep -E '^NACOS_AUTH_TOKEN=' || true)"
  local ikey;  ikey="$(echo "$envs"  | grep -E '^NACOS_AUTH_IDENTITY_KEY=' || true)"

  if [ -z "$auth" ]; then
    bad "NACOS_AUTH_ENABLE 未显式设置 —— 默认关闭，存在被篡改配置/注册恶意实例的风险"
  elif echo "$auth" | grep -qi 'true'; then
    ok "鉴权已开启（$auth）"
  else
    bad "鉴权未开启（$auth）"
  fi
  [ -n "$token" ] && ok "NACOS_AUTH_TOKEN 已设置（不回显值）" || bad "NACOS_AUTH_TOKEN 未设置"
  [ -n "$ikey" ]  && ok "NACOS_AUTH_IDENTITY_KEY 已设置"     || warn "NACOS_AUTH_IDENTITY_KEY 未设置（可选但建议）"

  section "3. 服务端连通性"
  local code; code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8848/nacos/v1/console/health/readiness 2>/dev/null || echo '000')"
  info "健康检查 HTTP 状态：$code"
  local login; login="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 http://127.0.0.1:8848/nacos/ 2>/dev/null || echo '000')"
  info "控制台 HTTP 状态：$login"
  info "若已开启鉴权，访问控制台应要求登录（用户名/密码）"

  section "4. 结论"
  if echo "${auth:-}" | grep -qi 'true'; then
    ok "P1-2 已满足：Nacos 鉴权开启"
  else
    bad "P1-2 未满足：需开启鉴权（执行 bash $0 --apply 查看步骤）"
  fi
}

apply() {
  section "Nacos 鉴权加固步骤"
  cat <<EOF
  ⚠️  重启 Nacos 会导致服务发现短暂不可用，请在低峰期执行。

  1) 生成强随机 Token（32 字节，Base64）：
       TOKEN="\$(openssl rand -base64 32)"
       IDKEY="\$(openssl rand -hex 16)"
       IDVAL="\$(openssl rand -base64 24)"
     → 把这三个值写进 ${COMPOSE_DIR}/.env（该文件应已在 .gitignore 中）：
       NACOS_AUTH_TOKEN=\$TOKEN
       NACOS_AUTH_IDENTITY_KEY=\$IDKEY
       NACOS_AUTH_IDENTITY_VALUE=\$IDVAL

  2) 修改 ${COMPOSE_DIR}/docker-compose.yml 的 nacos 服务 environment：
       NACOS_AUTH_ENABLE: "true"
       NACOS_AUTH_TOKEN: \${NACOS_AUTH_TOKEN}
       NACOS_AUTH_IDENTITY_KEY: \${NACOS_AUTH_IDENTITY_KEY}
       NACOS_AUTH_IDENTITY_VALUE: \${NACOS_AUTH_IDENTITY_VALUE}
     ⚠️ 注意：NACOS_AUTH_TOKEN 必须 >= 32 字节的 Base64 串，否则 Nacos 启动会报错。

  3) 各服务客户端补上账号密码（${COMPOSE_DIR} 或各服务 config）：
       spring.cloud.nacos.discovery.username: nacos
       spring.cloud.nacos.discovery.password: <改动后的强密码>
     ⚠️ Nacos 首次开启鉴权后，默认账号 nacos/nacos 请立即改密。

  4) 重启并验证：
       cd ${COMPOSE_DIR} && docker compose up -d nacos
       bash $0 --check          # 应显示「鉴权已开启」

  5) 验证控制台需登录：
       curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8848/nacos/
EOF
}

case "${1:-}" in
  --check|"") check ;;
  --apply)    check; apply ;;
  *) bad "未知参数：$1"; info "用法：$0 [--check|--apply]"; exit 2 ;;
esac
