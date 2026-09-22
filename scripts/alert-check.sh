#!/bin/bash
# =============================================================
# 五零时光 告警检查脚本
#
# 用途：检查是否存在未处理的 CRITICAL 告警（当前主要是资金对账异常）。
# 设计：对账任务写入 ALERT_PENDING 标记文件，本脚本读取并：
#   1. 有告警 -> 输出内容并 exit 1（便于 cron 邮件 / 监控系统捕获）
#   2. 无告警 -> exit 0
#
# 部署：/opt/wuling/scripts/alert-check.sh
# 定时：见 /etc/cron.d/wuling-alert
#
# 说明：服务器 25 端口被封，postfix 无法直投外部邮箱；
#       当前通过本脚本的 exit code 供外部监控（cron MAILTO / 监控系统）判断。
# =============================================================
set -uo pipefail

FLAG_FILE="/opt/wuling/logs/ALERT_PENDING"
ALERT_LOG="/opt/wuling/logs/alerts.log"

if [ ! -f "$FLAG_FILE" ]; then
  exit 0
fi

COUNT=$(wc -l < "$FLAG_FILE" 2>/dev/null || echo 0)
if [ "$COUNT" -eq 0 ]; then
  exit 0
fi

echo "=========================================="
echo " 五零时光 未处理告警: $COUNT 条"
echo " 时间: $(date "+%Y-%m-%d %H:%M:%S")"
echo "=========================================="
tail -20 "$FLAG_FILE"
echo "=========================================="
echo "完整告警日志: $ALERT_LOG"
echo "处理方式: 核查 reconcile_issue 表，处理后调用"
echo "  POST /api/v1/admin/finance/reconcile/issues/{id}/resolve?status=RESOLVED"
echo "并在全部处理后清空标记文件: > $FLAG_FILE"
echo "=========================================="

exit 1