#!/usr/bin/env bash
# =============================================================
# 五零时光 · P2-2  数据备份（勒索的最后兜底）
#
# 目标：每日自动备份 MySQL + Redis，并支持异地同步 + 恢复演练。
#
# 用法：
#   set -a; . /opt/wuling/app/secrets.env; set +a
#   bash deploy/security/04-backup.sh --check     # 检查备份配置与现状（只读）
#   bash deploy/security/04-backup.sh --run       # 立即执行一次备份
#   bash deploy/security/04-backup.sh --install   # 安装每日定时任务（crontab）
#   bash deploy/security/04-backup.sh --restore-test <备份文件>  # 恢复演练（还原到临时库）
#
# 安全设计：
#   - 密码只从环境变量读取，不硬编码；
#   - 备份文件权限 600，避免其他用户读取；
#   - 支持 BACKUP_REMOTE 环境变量做异地 rsync（防「同机备份一起被加密」）。
# =============================================================
set -uo pipefail

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; NC=$'\033[0m'
section() { printf '\n%s===== %s =====%s\n' "$CYAN" "$1" "$NC"; }
ok()   { printf '%s[OK]%s %s\n'   "$GREEN"  "$NC" "$1"; }
warn() { printf '%s[WARN]%s %s\n' "$YELLOW" "$NC" "$1"; }
bad()  { printf '%s[RISK]%s %s\n' "$RED"    "$NC" "$1"; }
info() { printf '       %s\n' "$1"; }

BACKUP_DIR="${BACKUP_DIR:-/opt/wuling/backup}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-wuling-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-wuling-redis}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-30}"
MYSQL_DB="${MYSQL_DB:-wuling}"
# 异地备份目标，形如 backup@1.2.3.4:/data/wuling-backup/ ；留空则跳过（不推荐）
BACKUP_REMOTE="${BACKUP_REMOTE:-}"

ts() { date +%Y%m%d_%H%M%S; }

# ---------- 前置 ----------
precheck() {
  section "前置检查"
  command -v docker >/dev/null 2>&1 && ok "docker 可用" || { bad "未安装 docker"; exit 1; }
  docker ps --format '{{.Names}}' | grep -qx "$MYSQL_CONTAINER" && ok "$MYSQL_CONTAINER 运行中" || bad "$MYSQL_CONTAINER 未运行"
  docker ps --format '{{.Names}}' | grep -qx "$REDIS_CONTAINER" && ok "$REDIS_CONTAINER 运行中" || warn "$REDIS_CONTAINER 未运行"
  [ -n "${MYSQL_ROOT_PASSWORD:-}" ] && ok "MYSQL_ROOT_PASSWORD 已设置" || bad "MYSQL_ROOT_PASSWORD 未设置（请先 source secrets.env）"
}

# ---------- MySQL 备份 ----------
backup_mysql() {
  section "MySQL 全量备份"
  mkdir -p "$BACKUP_DIR/mysql"
  chmod 700 "$BACKUP_DIR"
  local out="$BACKUP_DIR/mysql/${MYSQL_DB}_$(ts).sql.gz"
  info "开始导出（--single-transaction，不锁表）..."
  if docker exec "$MYSQL_CONTAINER" sh -c \
       "exec mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --triggers --events --databases $MYSQL_DB" \
       2>/dev/null | gzip > "$out"; then
    chmod 600 "$out"
    local size; size="$(du -h "$out" | cut -f1)"
    # 校验：gzip 完整性 + 文件非空
    if gzip -t "$out" 2>/dev/null && [ -s "$out" ]; then
      ok "备份成功：$out（$size）"
    else
      bad "备份文件损坏或为空：$out"
      return 1
    fi
  else
    bad "mysqldump 失败（检查密码 / 容器名 / 数据库名）"
    return 1
  fi

  info "清理 $KEEP_DAYS 天前的旧备份..."
  find "$BACKUP_DIR/mysql" -name '*.sql.gz' -mtime "+$KEEP_DAYS" -delete 2>/dev/null || true
  ok "当前 MySQL 备份数：$(find "$BACKUP_DIR/mysql" -name '*.sql.gz' 2>/dev/null | wc -l)"
}

# ---------- Redis 备份 ----------
backup_redis() {
  section "Redis 持久化文件备份"
  mkdir -p "$BACKUP_DIR/redis"
  if ! docker ps --format '{{.Names}}' | grep -qx "$REDIS_CONTAINER"; then
    warn "$REDIS_CONTAINER 未运行，跳过"; return 0
  fi
  # 触发一次 RDB 落盘（BGSAVE 异步，不阻塞）
  if [ -n "${REDIS_PASSWORD:-}" ]; then
    docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" BGSAVE >/dev/null 2>&1 || true
    sleep 2
  fi
  local out="$BACKUP_DIR/redis/dump_$(ts).rdb"
  if docker cp "$REDIS_CONTAINER:/data/dump.rdb" "$out" 2>/dev/null; then
    chmod 600 "$out"
    ok "Redis 快照备份成功：$out（$(du -h "$out" | cut -f1)）"
  else
    warn "未取到 dump.rdb（可能未启用 RDB 持久化，仅 AOF）"
    # 兜底：尝试 AOF
    local aof="$BACKUP_DIR/redis/appendonly_$(ts).aof"
    if docker cp "$REDIS_CONTAINER:/data/appendonlydir" "$aof.dir" 2>/dev/null; then
      tar -czf "$aof.tar.gz" -C "$BACKUP_DIR/redis" "$(basename "$aof.dir")" 2>/dev/null && rm -rf "$aof.dir"
      chmod 600 "$aof.tar.gz"
      ok "Redis AOF 备份成功：$aof.tar.gz"
    fi
  fi
  find "$BACKUP_DIR/redis" -type f -mtime "+$KEEP_DAYS" -delete 2>/dev/null || true
}

# ---------- 异地同步 ----------
sync_remote() {
  section "异地/离线同步（防同机备份被一起加密）"
  if [ -z "$BACKUP_REMOTE" ]; then
    bad "未配置 BACKUP_REMOTE —— 备份与数据在同一台机器，勒索时会一起被加密！"
    info "  配置示例：export BACKUP_REMOTE='backup@10.0.0.9:/data/wuling-backup/'"
    info "  建议目标：另一台服务器 / 对象存储（rclone） / 离线磁盘"
    return 1
  fi
  if command -v rsync >/dev/null 2>&1; then
    if rsync -az --delete "$BACKUP_DIR/" "$BACKUP_REMOTE" 2>/dev/null; then
      ok "已同步到 $BACKUP_REMOTE"
    else
      bad "rsync 同步失败（检查免密登录与目标路径）"
    fi
  else
    warn "未安装 rsync"
  fi
}

# ---------- 恢复演练 ----------
restore_test() {
  local file="${1:-}"
  section "恢复演练（还原到临时库，不动生产库）"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    bad "用法：$0 --restore-test /opt/wuling/backup/mysql/xxx.sql.gz"
    return 1
  fi
  info "演练文件：$file"
  info "1/3 校验压缩包完整性..."
  gzip -t "$file" 2>/dev/null && ok "gzip 完整" || { bad "文件损坏"; return 1; }
  info "2/3 建临时库（演练用，用完即删）..."
  local tmpdb="restore_test_$(date +%s)"
  docker exec "$MYSQL_CONTAINER" sh -c \
    "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'CREATE DATABASE $tmpdb'" 2>/dev/null \
    && ok "临时库 $tmpdb 已创建" || { bad "建库失败"; return 1; }
  info "3/3 导入备份..."
  if gzip -dc "$file" | docker exec -i "$MYSQL_CONTAINER" sh -c \
       "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $tmpdb" 2>/dev/null; then
    local n; n="$(docker exec "$MYSQL_CONTAINER" sh -c \
      "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=\"$tmpdb\"'" 2>/dev/null)"
    ok "导入成功，共 $n 张表"
    info "清理临时库..."
    docker exec "$MYSQL_CONTAINER" sh -c \
      "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE $tmpdb'" 2>/dev/null \
      && ok "临时库已清理"
    [ "${n:-0}" -gt 0 ] && ok "恢复演练通过 ✅" || bad "表数为 0，备份可能不完整"
  else
    bad "导入失败 —— 该备份不可用，需排查"
    docker exec "$MYSQL_CONTAINER" sh -c \
      "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e 'DROP DATABASE IF EXISTS $tmpdb'" 2>/dev/null || true
    return 1
  fi
}

# ---------- 安装定时任务 ----------
install_cron() {
  section "安装每日备份定时任务"
  local self; self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  local line="0 3 * * * set -a; . /opt/wuling/app/secrets.env; set +a; bash $self --run >> /opt/wuling/backup/backup.log 2>&1"
  if crontab -l 2>/dev/null | grep -qF "$self"; then
    ok "定时任务已存在，跳过"
  else
    ( crontab -l 2>/dev/null; echo "$line" ) | crontab -
    ok "已安装：每天 03:00 自动备份"
  fi
  info "当前 crontab："
  crontab -l 2>/dev/null | sed 's/^/       /'
}

# ---------- 检查 ----------
check() {
  section "备份现状检查"
  if [ -d "$BACKUP_DIR" ]; then
    ok "备份目录存在：$BACKUP_DIR"
    info "最近 5 个备份："
    find "$BACKUP_DIR" -type f \( -name '*.gz' -o -name '*.rdb' \) -printf '       %TY-%Tm-%Td %TH:%TM  %p  (%s bytes)\n' 2>/dev/null | sort -r | head -5
  else
    bad "备份目录不存在：$BACKUP_DIR"
  fi
  if crontab -l 2>/dev/null | grep -qE 'backup'; then
    ok "已配置备份定时任务"
  else
    bad "未配置备份定时任务 —— 需执行：$0 --install"
  fi
  [ -n "$BACKUP_REMOTE" ] && ok "已配置异地同步：$BACKUP_REMOTE" \
    || bad "未配置异地同步（BACKUP_REMOTE）—— 同机备份无法抵御勒索"
}

case "${1:-}" in
  --check|"") check ;;
  --run)      precheck && backup_mysql && backup_redis && sync_remote ;;
  --install)  install_cron ;;
  --restore-test) restore_test "${2:-}" ;;
  *) bad "未知参数：$1"; info "用法：$0 [--check|--run|--install|--restore-test <file>]"; exit 2 ;;
esac
