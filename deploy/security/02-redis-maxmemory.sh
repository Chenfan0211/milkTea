#!/usr/bin/env bash
# =============================================================
# 五零时光 · P1-1  Redis 内存上限与淘汰策略加固
#
# 目标：防止 Redis 内存写满触发系统 OOM，导致服务整体崩溃。
#
# 用法：
#   set -a; . /opt/wuling/app/secrets.env; set +a     # 先导入密码
#   bash deploy/security/02-redis-maxmemory.sh              # 检查
#   bash deploy/security/02-redis-maxmemory.sh --apply      # 执行
#   bash deploy/security/02-redis-maxmemory.sh --verify     # 验证
#
# 安全设计：
#   - 密码只从环境变量 REDIS_PASSWORD 读取，绝不硬编码/回显；
#   - 默认「只检查不动手」，必须显式 --apply 才修改；
#   - 修改后立即 CONFIG REWRITE 持久化，避免重启丢失；
#   - 不重启容器（避免影响业务）。
# =============================================================
set -uo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; NC=$'\033[0m'
section() { printf '\n%s===== %s =====%s\n' "$CYAN" "$1" "$NC"; }
ok()   { printf '%s[OK]%s %s\n'   "$GREEN"  "$NC" "$1"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
bad()  { printf '%s[RISK]%s %s\n' "$RED"    "$NC" "$1"; }
info() { printf '       %s\n' "$1"; }

CONTAINER="${REDIS_CONTAINER:-wuling-redis}"
TARGET_MAXMEMORY="${REDIS_MAXMEMORY:-512mb}"
TARGET_POLICY="${REDIS_MAXMEMORY_POLICY:-allkeys-lru}"
MODE="${1:---check}"

rcli() { docker exec "$CONTAINER" redis-cli -a "$REDIS_PASSWORD" "$@" 2>/dev/null; }

# ---------- 前置检查 ----------
section "前置检查"
if ! command -v docker >/dev/null 2>&1; then
  bad "未安装 docker，无法继续"; exit 1
fi
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  bad "容器 $CONTAINER 未运行，无法继续"; exit 1
fi
if [ -z "${REDIS_PASSWORD:-}" ]; then
  bad "环境变量 REDIS_PASSWORD 未设置"
  info "请先执行： set -a; . /opt/wuling/app/secrets.env; set +a"
  exit 1
fi
ok "容器 $CONTAINER 运行中；REDIS_PASSWORD 已设置（长度 ${#REDIS_PASSWORD}）"
if [ "$(rcli PING | tail -1)" != "PONG" ]; then
  bad "Redis PING 失败：密码错误或服务异常"; exit 1
fi
ok "Redis 连接正常（PONG）"

# ---------- 检查：当前配置 ----------
print_current() {
  section "当前配置"
  local mm mp used
  mm="$(rcli CONFIG GET maxmemory | tail -1)"
  mp="$(rcli CONFIG GET maxmemory-policy | tail -1)"
  used="$(rcli INFO memory | grep -E '^used_memory_human:' | tr -d '\r' | cut -d: -f2)"
  info "maxmemory         = ${mm:-<空>}"
  info "maxmemory-policy  = ${mp:-<空>}"
  info "used_memory       = ${used:-<未知>}"
}

# ---------- 应用 ----------
apply_config() {
  section "执行加固"
  info "目标：maxmemory=$TARGET_MAXMEMORY, maxmemory-policy=$TARGET_POLICY"
  echo

  info "1/3 设置 maxmemory ..."
  if rcli CONFIG SET maxmemory "$TARGET_MAXMEMORY" | grep -q OK; then ok "maxmemory 已设为 $TARGET_MAXMEMORY"
  else bad "maxmemory 设置失败"; return 1; fi

  info "2/3 设置 maxmemory-policy ..."
  if rcli CONFIG SET maxmemory-policy "$TARGET_POLICY" | grep -q OK; then ok "maxmemory-policy 已设为 $TARGET_POLICY"
  else bad "maxmemory-policy 设置失败"; return 1; fi

  info "3/3 持久化到配置文件（CONFIG REWRITE）..."
  if rcli CONFIG REWRITE | grep -q OK; then ok "已写入 redis.conf（重启后仍生效）"
  else warn "CONFIG REWRITE 失败：若 Redis 未使用配置文件启动则属正常，但重启会丢失 —— 请在 compose 里补 command 参数"; return 1; fi
}

# ---------- 验证 ----------
verify() {
  section "验证"
  local mm mp
  mm="$(rcli CONFIG GET maxmemory | tail -1)"
  mp="$(rcli CONFIG GET maxmemory-policy | tail -1)"
  info "maxmemory        = $mm"
  info "maxmemory-policy = $mp"
  local pass=1
  [ "$mm" != "0" ] && [ -n "$mm" ] && ok "maxmemory 已生效（非 0）" || { bad "maxmemory 未生效"; pass=0; }
  [ "$mp" = "$TARGET_POLICY" ] && ok "maxmemory-policy 已生效" || { bad "maxmemory-policy 不符（期望 $TARGET_POLICY）"; pass=0; }

  # 确认关键业务 key 仍在（避免误判淘汰影响）
  info "抽样确认关键命名空间 key 是否可达："
  for ns in "wuling:auth:" "wuling:sms:" "wuling:cache:" "wuling:biz:"; do
    local n
    n="$(rcli --scan --pattern "${ns}*" | head -100 | wc -l)"
    info "  $ns*  取样命中 $n 个"
  done
  info "说明：allkeys-lru 在【内存未达上限时不会淘汰任何 key】，当前业务不受影响。"
  [ "$pass" = "1" ] && ok "P1-1 验证通过" || bad "P1-1 验证未通过，请检查上方输出"
}

# ---------- 入口 ----------
case "$MODE" in
  --check|"")
    print_current
    section "风险判定"
    mm="$(rcli CONFIG GET maxmemory | tail -1)"
    if [ "$mm" = "0" ] || [ -z "$mm" ]; then
      bad "Redis 未设置 maxmemory —— 存在内存写满触发 OOM 的风险"
      info "执行加固：bash $0 --apply"
    else
      ok "Redis 已设置 maxmemory=$mm"
    fi
    ;;
  --apply)
    print_current
    apply_config || exit 1
    verify
    ;;
  --verify)
    verify
    ;;
  *)
    bad "未知参数：$MODE"; info "用法：$0 [--check|--apply|--verify]"; exit 2
    ;;
esac
