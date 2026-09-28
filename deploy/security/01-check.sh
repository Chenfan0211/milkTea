#!/usr/bin/env bash
# =============================================================
# 五零时光 · 安全检查（只读，无副作用）
#
# 用途：在生产服务器上「先检查」——只读现状，不做任何修改。
#       把输出贴回来，据此给出针对性的加固命令。
#
# 用法：
#   bash deploy/security/01-check.sh
#
# 设计原则：
#   - 全程只读：不 SET、不 REWRITE、不重启任何容器；
#   - 不打印密码明文：只判断「是否为空/长度」，绝不回显值；
#   - 每一步都有明确标题，便于对照结果。
# =============================================================
set -uo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; NC=$'\033[0m'

section() { printf '\n%s===== %s =====%s\n' "$CYAN" "$1" "$NC"; }
ok()      { printf '%s[OK]%s %s\n'   "$GREEN"  "$NC" "$1"; }
warn()    { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
bad()     { printf '%s[RISK]%s %s\n' "$RED"    "$NC" "$1"; }
info()    { printf '       %s\n' "$1"; }

# 生产中间件 compose 目录（按项目约定，可通过环境变量覆盖）
DEPLOY_DIR="${DEPLOY_DIR:-/opt/wuling/deploy}"

# ---------- 1. 监听端口：是否只绑定回环 ----------
section "1. 中间件端口监听（判断是否暴露公网）"
if command -v ss >/dev/null 2>&1; then
  SS_OUT="$(ss -tlnp 2>/dev/null | grep -E ':(3306|6379|5672|15672|8848)\b' || true)"
else
  SS_OUT="$(netstat -tlnp 2>/dev/null | grep -E ':(3306|6379|5672|15672|8848)\b' || true)"
fi

if [ -z "$SS_OUT" ]; then
  warn "未匹配到中间件端口（可能端口不同或服务未运行）"
else
  echo "$SS_OUT"
  echo
  if echo "$SS_OUT" | grep -qE '(0\.0\.0\.0|\[::\]|\*):(3306|6379|5672|15672|8848)'; then
    bad "存在绑定 0.0.0.0 / :: 的中间件端口 —— 可能对公网暴露，必须收敛为 127.0.0.1"
  else
    ok "全部端口仅绑定 127.0.0.1，未对公网暴露"
  fi
fi

# ---------- 2. Docker 端口映射复核 ----------
section "2. Docker 端口映射（复核 compose 的绑定地址）"
if command -v docker >/dev/null 2>&1; then
  docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null | grep -E 'wuling-(mysql|redis|rabbitmq|nacos)' || warn "未找到 wuling-* 中间件容器"
  echo
  if docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null | grep -E 'wuling-(mysql|redis|rabbitmq|nacos)' | grep -qE '0\.0\.0\.0:'; then
    bad "容器端口映射到 0.0.0.0 —— 需改为 127.0.0.1:xxxx:xxxx"
  else
    ok "容器端口映射未使用 0.0.0.0"
  fi
else
  warn "未安装 docker，跳过"
fi

# ---------- 3. 系统防火墙 ----------
section "3. 系统防火墙状态（第二道防线）"
if command -v firewall-cmd >/dev/null 2>&1; then
  FWD="$(firewall-cmd --state 2>/dev/null || echo 'not-running')"
  info "firewalld: $FWD"
  [ "$FWD" = "running" ] && firewall-cmd --list-ports 2>/dev/null | sed 's/^/       open ports: /'
elif command -v ufw >/dev/null 2>&1; then
  info "ufw: $(ufw status 2>/dev/null | head -1)"
else
  warn "未检测到 firewalld / ufw —— 依赖云安全组，请确认安全组仅放行 80/443/SSH"
fi
info "提醒：若用云服务器，还需在控制台确认【安全组】未放行 3306/6379/5672/8848"

# ---------- 4. Redis 密码与配置 ----------
section "4. Redis 安全（密码强度 / 内存上限 / 淘汰策略）"
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'wuling-redis'; then
  if [ -z "${REDIS_PASSWORD:-}" ]; then
    warn "当前 shell 未设置 REDIS_PASSWORD，无法探测 Redis（可 export 后重跑，或用下方命令手动查）"
    info "手动查：docker exec wuling-redis redis-cli -a \"\$REDIS_PASSWORD\" CONFIG GET maxmemory"
  else
    PONG="$(docker exec wuling-redis redis-cli -a "$REDIS_PASSWORD" PING 2>/dev/null | tail -1)"
    [ "$PONG" = "PONG" ] && ok "Redis 密码验证通过（可用）" || bad "Redis 密码验证失败或 Redis 不可达"

    # 密码长度（不回显明文）
    if [ "${#REDIS_PASSWORD}" -ge 16 ]; then
      ok "REDIS_PASSWORD 长度 ${#REDIS_PASSWORD}（>=16，符合强口令）"
    else
      bad "REDIS_PASSWORD 长度仅 ${#REDIS_PASSWORD}，建议 >=16 位随机串"
    fi

    MM="$(docker exec wuling-redis redis-cli -a "$REDIS_PASSWORD" CONFIG GET maxmemory 2>/dev/null | tail -1)"
    MP="$(docker exec wuling-redis redis-cli -a "$REDIS_PASSWORD" CONFIG GET maxmemory-policy 2>/dev/null | tail -1)"
    if [ -z "$MM" ] || [ "$MM" = "0" ]; then
      bad "Redis 未设置 maxmemory（当前=$MM）—— 存在内存写满触发 OOM 的风险"
    else
      ok "Redis maxmemory = $MM"
    fi
    if [ "$MP" = "noeviction" ] || [ -z "$MP" ]; then
      warn "Redis maxmemory-policy = $MP（noeviction 会在写满时拒绝写入）"
    else
      ok "Redis maxmemory-policy = $MP"
    fi

    # 危险命令是否仍可用（提示项）
    info "提示：生产建议禁用/重命名 FLUSHALL/CONFIG/KEYS 等危险命令（可选加固）"
  fi
else
  warn "未找到 wuling-redis 容器"
fi

# ---------- 5. Nacos 鉴权 ----------
section "5. Nacos 鉴权（注册中心/配置中心不可裸奔）"
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'wuling-nacos'; then
  AUTH_ENV="$(docker inspect wuling-nacos --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | grep -E '^NACOS_AUTH_ENABLE=' || true)"
  if [ -z "$AUTH_ENV" ]; then
    bad "Nacos 未显式设置 NACOS_AUTH_ENABLE（默认可能为关闭）"
  elif echo "$AUTH_ENV" | grep -q 'true'; then
    ok "Nacos 鉴权已开启（$AUTH_ENV）"
  else
    bad "Nacos 鉴权未开启（$AUTH_ENV）—— 任何能访问 8848 的进程都可改配置/注册实例"
  fi
  info "验证：curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8848/nacos/v1/console/health/readiness"
else
  warn "未找到 wuling-nacos 容器（若未使用 Nacos 可忽略）"
fi

# ---------- 6. MySQL 密码强度 ----------
section "6. MySQL 口令强度"
if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  if [ "${#MYSQL_ROOT_PASSWORD}" -ge 16 ]; then
    ok "MYSQL_ROOT_PASSWORD 长度 ${#MYSQL_ROOT_PASSWORD}（>=16）"
  else
    bad "MYSQL_ROOT_PASSWORD 长度仅 ${#MYSQL_ROOT_PASSWORD}，建议 >=16 位随机串"
  fi
else
  warn "当前 shell 未设置 MYSQL_ROOT_PASSWORD，无法判断长度"
fi
if [ -n "${MYSQL_PASSWORD:-}" ]; then
  if [ "${#MYSQL_PASSWORD}" -ge 16 ]; then
    ok "MYSQL_PASSWORD（业务账号）长度 ${#MYSQL_PASSWORD}（>=16）"
  else
    bad "MYSQL_PASSWORD 长度仅 ${#MYSQL_PASSWORD}，建议 >=16 位随机串"
  fi
else
  warn "当前 shell 未设置 MYSQL_PASSWORD，无法判断长度"
fi

# ---------- 7. 备份现状 ----------
section "7. 备份现状（勒索的最后兜底）"
BK="${BACKUP_DIR:-/opt/wuling/backup}"
if [ -d "$BK" ]; then
  ok "备份目录存在：$BK"
  info "最近备份文件（最多 5 个）："
  find "$BK" -type f \( -name '*.sql.gz' -o -name '*.rdb' -o -name '*.aof' \) -printf '       %TY-%Tm-%Td %TH:%TM  %p  (%s bytes)\n' 2>/dev/null | sort -r | head -5
else
  bad "备份目录不存在（$BK）—— 一旦数据被加密将无法恢复"
fi
CRON="$(crontab -l 2>/dev/null | grep -vE '^\s*#' | grep -E 'backup|dump|rsync' || true)"
if [ -n "$CRON" ]; then
  ok "检测到备份相关定时任务："
  echo "$CRON" | sed 's/^/       /'
else
  bad "未检测到备份定时任务 —— 需配置每日备份"
fi
info "关键：备份必须【异地/离线】保存，同机备份会被一起加密"

# ---------- 8. SSH 加固现状 ----------
section "8. SSH 加固现状"
if [ -f /etc/ssh/sshd_config ]; then
  PR="$(grep -iE '^\s*PermitRootLogin' /etc/ssh/sshd_config | tail -1 || echo 'PermitRootLogin (未显式设置，默认 prohibit-password)')"
  PA="$(grep -iE '^\s*PasswordAuthentication' /etc/ssh/sshd_config | tail -1 || echo 'PasswordAuthentication (未显式设置)')"
  info "$PR"
  info "$PA"
  if echo "$PA" | grep -qi 'no'; then
    ok "已禁用 SSH 密码登录（仅密钥）"
  else
    warn "SSH 密码登录可能仍开启，建议改为仅密钥登录"
  fi
else
  warn "未找到 /etc/ssh/sshd_config"
fi
info "失效登录尝试次数（可选）：grep 'Failed password' /var/log/secure | wc -l"

# ---------- 汇总 ----------
section "检查完成"
cat <<'EOF'
请把以上完整输出贴回，重点关注带 [RISK] / [WARN] 的行。

补充说明：
  - 若某些项显示「未设置环境变量」，可在服务器上先执行：
      set -a; . /opt/wuling/app/secrets.env; set +a
    再重跑本脚本（该文件为生产密钥文件，本脚本只读取、不回显明文）。
  - 本脚本全程只读：不修改任何配置、不重启任何服务。
EOF
