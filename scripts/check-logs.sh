#!/bin/bash
# =============================================================
# 五零时光 · 日志体检脚本（logback 落盘健康检查）
#
# 用途：检查 8 个后端服务是否正常写日志，防止「文件日志静默失败」
#       （logback 写文件失败默认不报错，docker logs 里也看不到）。
#
# 检查项：
#   1. 当天活跃日志 ${SERVICE}.log 是否存在；
#   2. 文件最近写入时间是否超过 STALE_MINUTES（默认 30 分钟），
#      超时说明服务可能卡死或日志停止落盘；
#   3. 输出各服务当天日志大小，便于发现某服务日志异常膨胀；
#   4. 汇报日志目录总体占用与归档文件数量。
#
# 退出码：
#   0 = 全部正常
#   1 = 存在异常（缺文件或超过 STALE_MINUTES 未写入），供 cron / 监控捕获
#
# 用法：
#   ./check-logs.sh                          # 立即体检
#   LOG_DIR=/path STALE_MINUTES=15 ./check-logs.sh   # 自定义目录/阈值
#
# 定时安装（服务器 root 执行一次即可，每 10 分钟巡检）：
#   cat > /etc/cron.d/wuling-log-check <<'EOF'
#   SHELL=/bin/bash
#   PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
#   */10 * * * * root /opt/wuling/scripts/check-logs.sh >>/var/log/wuling-log-check.log 2>&1
#   EOF
#   chmod 644 /etc/cron.d/wuling-log-check
# =============================================================
set -uo pipefail

LOG_DIR="${LOG_DIR:-/opt/wuling/logs}"
STALE_MINUTES="${STALE_MINUTES:-30}"

# 8 个后端服务名（与 Dockerfile 的 SERVICE 一致）
SERVICES="gateway auth-service file-service marketing-service trade-service user-service product-service server"

log() { echo "[check-logs] $*"; }

if [ ! -d "$LOG_DIR" ]; then
  log "日志目录不存在：$LOG_DIR"
  exit 1
fi

# 校验 STALE_MINUTES 为正整数
case "$STALE_MINUTES" in
  ''|*[!0-9]*) log "非法超时分钟数：$STALE_MINUTES"; exit 2 ;;
esac

NOW=$(date +%s)
ISSUES=0

echo "=========================================="
echo " 五零时光 日志体检 $(date '+%Y-%m-%d %H:%M:%S')"
echo " 目录: $LOG_DIR  超时阈值: ${STALE_MINUTES} 分钟"
echo "=========================================="

for SVC in $SERVICES; do
  F="$LOG_DIR/$SVC.log"
  if [ ! -f "$F" ]; then
    echo " [异常] $SVC：当天日志不存在 ($F)"
    ISSUES=$((ISSUES+1))
    continue
  fi

  SIZE=$(du -h "$F" 2>/dev/null | cut -f1)
  MTIME=$(stat -c %Y "$F" 2>/dev/null || stat -f %m "$F" 2>/dev/null || echo 0)

  if [ "$MTIME" = "0" ]; then
    echo " [异常] $SVC：无法读取修改时间 ($F)"
    ISSUES=$((ISSUES+1))
    continue
  fi

  AGE_MIN=$(( (NOW - MTIME) / 60 ))
  if [ "$AGE_MIN" -gt "$STALE_MINUTES" ]; then
    echo " [异常] $SVC：${AGE_MIN} 分钟未写入 (大小 $SIZE)"
    ISSUES=$((ISSUES+1))
  else
    echo " [正常] $SVC：${AGE_MIN} 分钟前写入 (大小 $SIZE)"
  fi
done

# 归档文件数量（按天+序号命名：*.yyyy-MM-dd.N.log 或 *.yyyy-MM-dd.log）
ARCHIVED=$(find "$LOG_DIR" -maxdepth 1 -type f \
  \( -name '*.20[0-9][0-9]-[0-9][0-9]-[0-9][0-9].log' -o \
     -name '*.20[0-9][0-9]-[0-9][0-9]-[0-9][0-9].[0-9]*.log' \) | wc -l)

echo "------------------------------------------"
echo " 归档日志文件数: $ARCHIVED"
if command -v du >/dev/null 2>&1; then
  du -sh "$LOG_DIR" 2>/dev/null | sed 's/^/ 日志目录总占用: /'
fi
echo "=========================================="

if [ "$ISSUES" -gt 0 ]; then
  log "发现 $ISSUES 处异常"
  exit 1
fi

log "全部正常"
exit 0
