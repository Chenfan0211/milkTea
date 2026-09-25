#!/bin/bash
# =============================================================
# 五零时光 · 日志清理脚本（按天归档，最长保留 90 天）
#
# 背景：
#   后端服务（logback）按天滚动日志，当天写 ${SERVICE}.log，
#   跨天归档为 ${SERVICE}.yyyy-MM-dd.log（见各服务的 logback-spring.xml）。
#   本脚本周期性删除「超过保留天数的归档日志」，满足「最长保留 3 个月」。
#
# 保留规则（重要）：
#   - 只清理「按天归档」的文件，即文件名匹配 *.yyyy-MM-dd.log；
#   - 当天活跃日志（${SERVICE}.log，无日期后缀）永不删除；
#   - 业务告警文件 alerts.log、标记文件 ALERT_PENDING、health.log 等
#     不在清理范围（它们非轮转、体量小、是告警核心，另由 alert-check.sh 管理）。
#
# 保留天数：默认 90，可用环境变量 LOG_RETENTION_DAYS 覆盖。
#
# 用法：
#   ./clean-logs.sh            # 立即执行一次清理（默认 /opt/wuling/logs，90 天）
#   LOG_DIR=/path LOG_RETENTION_DAYS=30 ./clean-logs.sh   # 指定目录与天数
#
# 定时安装（服务器 root 执行一次即可，每天凌晨 03:10 清理）：
#   cat > /etc/cron.d/wuling-logs <<'EOF'
#   SHELL=/bin/bash
#   PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
#   10 3 * * * root /opt/wuling/scripts/clean-logs.sh >>/var/log/wuling-clean.log 2>&1
#   EOF
#   chmod 644 /etc/cron.d/wuling-logs
# =============================================================
set -uo pipefail

LOG_DIR="${LOG_DIR:-/opt/wuling/logs}"
RETENTION_DAYS="${LOG_RETENTION_DAYS:-90}"
DRY_RUN="${DRY_RUN:-false}"

log() { echo "[clean-logs] $*"; }

if [ ! -d "$LOG_DIR" ]; then
  log "日志目录不存在：$LOG_DIR，跳过"
  exit 0
fi

# 校验保留天数为正整数
case "$RETENTION_DAYS" in
  ''|*[!0-9]*) log "非法保留天数：$RETENTION_DAYS"; exit 2 ;;
esac

log "清理目标目录：$LOG_DIR（保留最近 ${RETENTION_DAYS} 天）"

# 删除「修改时间早于 N 天」的按天归档日志。
# 用 -name '*.20xx-xx-xx.log' 精确匹配按天归档命名，
# 不会误删 ${SERVICE}.log / alerts.log / ALERT_PENDING / health.log。
DELETED=0
while IFS= read -r -d '' f; do
  if [ "$DRY_RUN" = "true" ]; then
    echo "  [dry-run] 将删除: $f"
  else
    rm -f -- "$f" && { echo "  删除: $f"; DELETED=$((DELETED+1)); }
  fi
done < <(find "$LOG_DIR" -maxdepth 1 -type f \
  -name '*.20[0-9][0-9]-[0-9][0-9]-[0-9][0-9].log' \
  -mtime +"${RETENTION_DAYS}" -print0)

if [ "$DRY_RUN" = "true" ]; then
  log "dry-run 完成（未实际删除）"
else
  log "清理完成，删除 ${DELETED} 个归档日志"
fi

# 汇报当前磁盘占用
if command -v du >/dev/null 2>&1; then
  du -sh "$LOG_DIR" 2>/dev/null | sed 's/^/  当前日志目录占用: /'
fi
