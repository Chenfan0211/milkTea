#!/usr/bin/env bash
# =============================================================
# 五零时光 · 服务器安全加固 总入口
#
# 按「先检查、后执行、再验证」的顺序，一键完成各步。
#
# 用法：
#   # 第一步：只读检查（推荐先跑这个，把输出贴回）
#   bash deploy/security/run.sh --check
#
#   # 第二步：按顺序加固（每步会让你确认）
#   bash deploy/security/run.sh --harden
#
# 加固顺序（按风险从高到低）：
#   P2-1 中间件暴露面 + 口令强度   ← 勒索最直接入口
#   P2-2 备份 + 恢复演练           ← 最后兜底
#   P1-1 Redis maxmemory           ← 防 OOM 崩溃
#   P1-2 Nacos 鉴权                ← 防配置篡改
#   P2-3 SSH 加固                  ← 防入侵拿 shell
# =============================================================
set -uo pipefail

CYAN=$'\033[36m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; NC=$'\033[0m'
section() { printf '\n%s########## %s ##########%s\n' "$CYAN" "$1" "$NC"; }
ok()   { printf '%s[OK]%s %s\n'   "$GREEN"  "$NC" "$1"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
bad()  { printf '%s[RISK]%s %s\n' "$RED"    "$NC" "$1"; }

DIR="$(cd "$(dirname "$0")" && pwd)"

# 加载生产密钥（若存在），供各子脚本读取；不打印任何值
SECRETS="${SECRETS_FILE:-/opt/wuling/app/secrets.env}"
if [ -f "$SECRETS" ]; then
  set -a; . "$SECRETS"; set +a
  ok "已加载密钥文件：$SECRETS（不回显）"
else
  warn "未找到密钥文件：$SECRETS"
  warn "部分检查项会因缺少口令变量而跳过。可手动：set -a; . <路径>; set +a"
fi

MODE="${1:---check}"

run_check() {
  section "P2-1 中间件暴露面 + 口令强度"
  bash "$DIR/03-middleware-hardening.sh" || true

  section "P1-1 Redis 内存与淘汰策略"
  bash "$DIR/02-redis-maxmemory.sh" --check || true

  section "P2-2 备份现状"
  bash "$DIR/04-backup.sh" --check || true

  section "P1-2 Nacos 鉴权"
  bash "$DIR/05-nacos-auth.sh" --check || true

  section "P2-3 SSH 加固现状"
  bash "$DIR/03-middleware-hardening.sh" --ssh-fix 2>/dev/null | sed -n '/SSH 加固步骤/,$p' || true

  section "综合检查完成"
  cat <<'EOF'
请把以上完整输出贴回，重点看 [RISK] 行。

后续（确认无误后）：
  bash deploy/security/run.sh --harden
EOF
}

run_harden() {
  warn "即将按顺序执行加固。每步执行前会提示，请确认后继续。"
  echo

  section "P2-1 中间件暴露面（只检查，不自动改配置）"
  bash "$DIR/03-middleware-hardening.sh" || true
  printf '\n%s>> 若上方有 [RISK]，请先按提示手动处理再继续。按 Enter 继续下步，Ctrl+C 中止...%s' "$YELLOW" "$NC"
  read -r _

  section "P2-2 备份（立即执行一次 + 安装定时任务）"
  bash "$DIR/04-backup.sh" --run
  bash "$DIR/04-backup.sh" --install
  printf '\n%s>> 强烈建议再执行一次恢复演练：%s\n' "$YELLOW" "$NC"
  echo "   bash $DIR/04-backup.sh --restore-test <刚生成的备份文件>"

  section "P1-1 Redis maxmemory"
  bash "$DIR/02-redis-maxmemory.sh" --apply
  bash "$DIR/02-redis-maxmemory.sh" --verify

  section "P1-2 Nacos 鉴权"
  bash "$DIR/05-nacos-auth.sh" --apply
  printf '\n%s>> Nacos 需按上方步骤改 compose 后重启，属人工确认操作。%s\n' "$YELLOW" "$NC"

  section "P2-3 SSH 加固"
  bash "$DIR/03-middleware-hardening.sh" --ssh-fix 2>/dev/null | sed -n '/SSH 加固步骤/,$p' || true
  printf '\n%s>> SSH 改动务必保持当前会话不关闭，另开终端验证后再关。%s\n' "$RED" "$NC"

  section "加固流程结束"
  echo "复查：bash $DIR/run.sh --check"
}

case "$MODE" in
  --check|"") run_check ;;
  --harden)   run_harden ;;
  *) bad "未知参数：$MODE"; echo "用法：$0 [--check|--harden]"; exit 2 ;;
esac
