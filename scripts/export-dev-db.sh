#!/usr/bin/env bash
# =============================================================
# 生产库结构导出脚本（仅结构与种子数据，不含业务数据）
#
# 用途：为本地开发准备一份独立的数据库快照。
# 默认排除订单、用户等业务数据，避免生产数据落到开发机。
#
# 用法（在服务器上执行）：
#   bash export-dev-db.sh /opt/wuling/backup/dev
# =============================================================
set -euo pipefail

OUT_DIR="${1:-/opt/wuling/backup/dev}"
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT_DIR"

ROOT_PW=$(grep 'MYSQL_ROOT_PASSWORD' /opt/wuling/deploy/docker-compose.yml | awk '{print $2}')

echo "=== 1. 导出表结构（不含数据）==="
docker exec wuling-mysql mysqldump -uroot -p"$ROOT_PW" \
  --no-data --routines --triggers --single-transaction \
  wuling > "$OUT_DIR/schema-$STAMP.sql" 2>/dev/null

echo "=== 2. 导出种子数据（字典/商品/门店等基础表，不含业务数据）==="
# 仅导出基础配置类表；订单/用户/资金等业务表不导出
docker exec wuling-mysql mysqldump -uroot -p"$ROOT_PW" \
  --no-create-info --single-transaction --skip-extended-insert \
  wuling \
  sys_dict_item region feature_flag product_category spec_group_template \
  spec_option_template product product_spec product_store \
  member_level split_rule app_config stored_value_package \
  points_signin_rule points_earning_rule points_product \
  coupon gift_card_denomination \
  > "$OUT_DIR/seed-$STAMP.sql" 2>/dev/null

echo "=== 3. 导出 flyway 历史（保证本地迁移版本与生产一致）==="
docker exec wuling-mysql mysqldump -uroot -p"$ROOT_PW" \
  --no-create-info --single-transaction \
  wuling flyway_schema_history > "$OUT_DIR/flyway-$STAMP.sql" 2>/dev/null

echo ""
echo "导出完成：$OUT_DIR"
ls -lh "$OUT_DIR"/{schema,seed,flyway}-$STAMP.sql
echo ""
echo "本地导入方式（需先装 MySQL 8）："
echo "  mysql -uroot -p -e 'create database wuling'"
echo "  mysql -uroot -p wuling < schema-$STAMP.sql"
echo "  mysql -uroot -p wuling < seed-$STAMP.sql"
echo "  mysql -uroot -p wuling < flyway-$STAMP.sql"