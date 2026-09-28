#!/usr/bin/env bash
# =============================================================
# 五零时光 · P2-1  中间件暴露面与口令强度加固
#
# 目标：确认 MySQL / Redis / RabbitMQ / Nacos 未暴露公网 + 口令足够强，
#       这是「防勒索病毒」最直接的一道防线。
#
# 用法：
#   set -a; . /opt/wuling/app/secrets.env; set +a
#   bash deploy/security/03-middleware-hardening.sh            # 检查（只读）
#   bash deploy/security/03-middleware-hardening.sh --ssh-fix  # 生成 SSH 加固建议
#
# 安全设计：
#   - 全程只读：不修改任何中间件配置；
#   - 密码只判断长度与是否为空，绝不回显明文；
#   - 只输出「需要你手动执行」的命令，不自动执行高危操作。
# =============================================================
set -uo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; NC=$'\033[0m'
section() { printf '\n%s===== %s =====%s\n' "$CYAN" "$1" "$NC"; }
ok()   { printf '%s[OK]%s %s\n'   "$GREEN"  "$NC" "$1"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
bad()  { printf '%s[RISK]%s %s\n' "$RED"    "$NC" "$1"; }
info() { printf '       %s\n' "$1"; }

# 口令强度：>=16 位；且不能是常见弱口令
check_pwd() {
  local name="$1" val="${2:-}"
  if [ -z "$val" ]; then
    bad "$name 未设置或为空"
    return
  fi
  local len=${#val}
  if [ "$len" -lt 16 ]; then
    bad "$name 长度仅 $len（建议 >=16）"
  else
    ok "$name 长度 $len（>=16）"
  fi
  case "$val" in
    123456|password|root|admin|redis|mysql|12345678|qwerty|abc123)
      bad "$name 疑似常见弱口令 —— 必须更换" ;;
  esac
}

# ---------- 1. 监听地址（暴露面） ----------
section "1. 中间件监听地址（是否暴露公网）"
PORTS_RE=':(3306|6379|5672|15672|8848)\b'
SS_OUT="$(ss -tlnp 2>/dev/null | grep -E "$PORTS_RE" || true)"
if [ -z "$SS_OUT" ]; then
  warn "未匹配到中间件端口（端口不同或服务未运行）"
else
  echo "$SS_OUT" | sed 's/^/       /'
  if echo "$SS_OUT" | grep -qE '(0\.0\.0\.0|\[::\]|\*):(3306|6379|5672|15672|8848)'; then
    bad "存在 0.0.0.0/:: 绑定 —— 必须收敛为 127.0.0.1"
    info "修复：在 deploy/docker-compose.yml 把 ports 改为 \"127.0.0.1:6379:6379\" 形式，然后 docker compose up -d"
  else
    ok "全部仅绑定 127.0.0.1（未对公网暴露）"
  fi
fi

# ---------- 2. Docker 端口映射 ----------
section "2. Docker 端口映射复核"
if command -v docker >/dev/null 2>&1; then
  MAP="$(docker ps --format '{{.Names}}\t{{.Ports}}' 2>/dev/null | grep -E 'wuling-(mysql|redis|rabbitmq|nacos)' || true)"
  if [ -z "$MAP" ]; then warn "未找到中间件容器"; else echo "$MAP" | sed 's/^/       /'; fi
  if echo "$MAP" | grep -qE '0\.0\.0\.0:'; then
    bad "容器端口映射到 0.0.0.0 —— 需改为 127.0.0.1:宿主机端口:容器端口"
  else
    ok "容器端口映射未使用 0.0.0.0"
  fi
else
  warn "未安装 docker"
fi

# ---------- 3. 云安全组提醒 ----------
section "3. 云安全组 / 防火墙（第二道防线）"
info "本脚本无法读取云控制台，请手动确认【安全组】只放行 80 / 443 / 22(SSH)"
info "不要放行：3306(MySQL) / 6379(Redis) / 5672,15672(RabbitMQ) / 8848(Nacos)"
if command -v firewall-cmd >/dev/null 2>&1; then
  info "firewalld 状态：$(firewall-cmd --state 2>/dev/null || echo not-running)"
elif command -v ufw >/dev/null 2>&1; then
  info "ufw 状态：$(ufw status 2>/dev/null | head -1)"
else
  warn "未检测到本机防火墙，完全依赖云安全组（务必确认）"
fi

# ---------- 4. 口令强度 ----------
section "4. 中间件口令强度（只判断长度，不回显）"
check_pwd "REDIS_PASSWORD"      "${REDIS_PASSWORD:-}"
check_pwd "MYSQL_ROOT_PASSWORD" "${MYSQL_ROOT_PASSWORD:-}"
check_pwd "MYSQL_PASSWORD"      "${MYSQL_PASSWORD:-}"
check_pwd "RABBITMQ_PASSWORD"   "${RABBITMQ_PASSWORD:-}"
if [ -z "${REDIS_PASSWORD:-}${MYSQL_ROOT_PASSWORD:-}${MYSQL_PASSWORD:-}${RABBITMQ_PASSWORD:-}" ]; then
  warn "当前 shell 未导入任何口令变量"
  info "请先执行： set -a; . /opt/wuling/app/secrets.env; set +a"
fi

# ---------- 5. Redis 危险命令 ----------
section "5. Redis 危险命令（可选加固）"
if [ -n "${REDIS_PASSWORD:-}" ] && command -v docker >/dev/null 2>&1 \
   && docker ps --format '{{.Names}}' | grep -qx 'wuling-redis'; then
  for cmd in FLUSHALL FLUSHDB CONFIG KEYS; do
    local_out="$(docker exec wuling-redis redis-cli -a "$REDIS_PASSWORD" COMMAND INFO "$cmd" 2>/dev/null | head -1)"
    if [ -n "$local_out" ]; then
      warn "$cmd 仍可用 —— 生产建议 rename-command 禁用/改名（需改 redis.conf 并重启）"
    else
      ok "$cmd 已禁用/改名"
    fi
  done
else
  warn "跳过（缺少 REDIS_PASSWORD 或容器）"
fi

# ---------- 6. 数据卷位置（勒索影响面） ----------
section "6. 数据卷位置（评估被加密的影响面）"
if command -v docker >/dev/null 2>&1; then
  for v in deploy_mysql-data deploy_redis-data deploy_rabbitmq-data; do
    mp="$(docker volume inspect "$v" --format '{{.Mountpoint}}' 2>/dev/null || true)"
    if [ -n "$mp" ]; then info "$v -> $mp"; else warn "$v 不存在（名称可能不同，用 docker volume ls 确认）"; fi
  done
fi
info "关键：备份目录不要与数据卷在同一磁盘/同一台机器"

# ---------- 7. SSH 加固建议 ----------
section "7. SSH 加固现状"
if [ -f /etc/ssh/sshd_config ]; then
  info "$(grep -iE '^\s*PermitRootLogin' /etc/ssh/sshd_config | tail -1 || echo 'PermitRootLogin: 未显式设置（默认 prohibit-password）')"
  info "$(grep -iE '^\s*PasswordAuthentication' /etc/ssh/sshd_config | tail -1 || echo 'PasswordAuthentication: 未显式设置（默认 yes）')"
  pa="$(grep -iE '^\s*PasswordAuthentication' /etc/ssh/sshd_config | tail -1 || true)"
  if echo "$pa" | grep -qi 'no'; then ok "已禁用密码登录"; else warn "建议禁用密码登录，仅用密钥"; fi
fi

if [ "${1:-}" = "--ssh-fix" ]; then
  section "SSH 加固步骤（手动执行）"
  cat <<'EOF'
  1) 确认已能用密钥登录（另开一个终端验证！），再执行：
       cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%F)
       sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
       sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/'    /etc/ssh/sshd_config
       sshd -t && systemctl restart sshd
  2) 保持当前会话不要关闭，另开终端验证能登录，再关旧会话。
  3) 可选：apt install fail2ban -y && systemctl enable --now fail2ban
EOF
fi

section "P2-1 检查完成"
info "把输出贴回；带 [RISK] 的项需要立即处理。"
