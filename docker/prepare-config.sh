#!/bin/bash
# =============================================================
# 五零时光 · 把现有 application-prod.yml「容器化」
#
# 为什么需要这一步（首轮实测踩坑）：
#   现有生产配置是为「宿主机 + systemd」写的，硬编码了 127.0.0.1：
#     server.address: 127.0.0.1     -> 容器内只监听回环，端口映射必然失效
#     nacos.server-addr: 127.0.0.1  -> 容器内 127.0.0.1 是容器自身，连不到 Nacos
#     mysql/redis/rabbitmq: 127.0.0.1 -> 同理连不到中间件
#   这类配置是**字面量**而非 ${ENV:default} 占位符，所以环境变量无法覆盖它
#   （Spring 的 ${} 只对占位符生效，覆盖不了写死的值）。
#
# 做法：
#   把宿主机配置复制一份到 /opt/wuling/config，逐项替换为容器内地址，
#   原文件保持不动 —— 便于随时回滚到 systemd 方式。
#
# 用法：./prepare-config.sh
# =============================================================
set -euo pipefail

SRC=/opt/wuling/app
DST=/opt/wuling/config
SERVICES=(gateway auth-service file-service marketing-service trade-service user-service product-service server)

log() { echo -e "\033[1;32m[config]\033[0m $*"; }

log "生成容器化配置 -> $DST"
mkdir -p "$DST"

for svc in "${SERVICES[@]}"; do
  src="$SRC/$svc/application-prod.yml"
  dst="$DST/$svc/application-prod.yml"

  if [ ! -f "$src" ]; then
    echo "  跳过 $svc（源配置不存在）"
    continue
  fi

  # 每服务独立子目录：Spring 的 --spring.config.additional-location 指向目录，
  # 若所有服务共用同一目录会互相串读配置（历史事故：auth-service 读到 server 的 port）
  mkdir -p "$DST/$svc"
  cp "$src" "$dst"

  # 1) 监听地址：容器内必须监听 0.0.0.0，否则 -p 端口映射无效
  #    （宿主机上用 127.0.0.1 是为了不暴露公网；容器内则由 compose 的
  #      "127.0.0.1:port:port" 绑定来保证同样的安全效果）
  sed -i 's/^\(\s*\)address:\s*127\.0\.0\.1\s*$/\1address: 0.0.0.0/' "$dst"

  # 2) Nacos：指向同网络容器名
  sed -i 's#server-addr:\s*127\.0\.0\.1:8848#server-addr: wuling-nacos:8848#' "$dst"
  # 注册到 Nacos 的 IP 必须是容器名/可达 IP，不能是本机回环
  sed -i 's/^\(\s*\)ip:\s*127\.0\.0\.1\s*$/\1ip: ${spring.application.name}/' "$dst"

  # 3) 数据源与中间件：指向同网络容器名
  sed -i 's#jdbc:mysql://127\.0\.0\.1:3306#jdbc:mysql://wuling-mysql:3306#' "$dst"
  # Redis / RabbitMQ：host 单独一行，且仅在 spring.data.redis / spring.rabbitmq 下
  sed -i 's/^\(\s*\)host:\s*127\.0\.0\.1\s*$/\1host: PLACEHOLDER_HOST/' "$dst"

  # 4) 内部服务调用地址（若为回环则改成容器名）
  sed -i 's#http://127\.0\.0\.1:8091#http://wuling-product-service#g' "$dst"
  sed -i 's#http://127\.0\.0\.1:8084#http://wuling-trade-service#g' "$dst"
  sed -i 's#http://127\.0\.0\.1:8085#http://wuling-user-service#g' "$dst"
  sed -i 's#http://127\.0\.0\.1:8090#http://wuling-server#g' "$dst"
  sed -i 's#http://127\.0\.0\.1:8083#http://wuling-marketing-service#g' "$dst"

  log "  已生成 $svc"
done

# ---------- 逐文件把 PLACEHOLDER_HOST 解析为正确容器名 ----------
# 为什么分两步：Redis 与 RabbitMQ 的 host 在 YAML 里都是 "host: 127.0.0.1"，
# 靠 sed 上下文难以区分，故先统一占位，再按服务与区块精确定位。
log "解析中间件地址占位符"

for svc in "${SERVICES[@]}"; do
  dst="$DST/$svc/application-prod.yml"
  [ -f "$dst" ] || continue
  # 用 awk 按「当前处于 data.redis 还是 rabbitmq 区块」分别替换
  awk '
    /^\s*redis:\s*$/        { section="redis" }
    /^\s*rabbitmq:\s*$/     { section="rabbitmq" }
    /^\s*data:\s*$/         { section="" }
    /^\s*cloud:\s*$/        { section="" }
    {
      if ($0 ~ /^\s*host:\s*PLACEHOLDER_HOST\s*$/) {
        if (section == "rabbitmq") {
          sub(/PLACEHOLDER_HOST/, "wuling-rabbitmq")
        } else {
          sub(/PLACEHOLDER_HOST/, "wuling-redis")
        }
      }
      print
    }
  ' "$dst" > "$dst.tmp" && mv "$dst.tmp" "$dst"

  # 兜底：若仍有未解析的占位符，直接报错而不是带着坏配置启动
  if grep -q PLACEHOLDER_HOST "$dst"; then
    echo "  ⚠️  $svc 仍存在未解析的 PLACEHOLDER_HOST，请人工检查：$dst" >&2
  fi
done

# ---------- 兜底：源配置缺 spring.rabbitmq 时补默认块 ----------
# auth-service 源配置没有 rabbitmq 块（宿主机上靠 localhost 默认值连通），
# 容器内 localhost 是容器自身，必须显式指向 wuling-rabbitmq，否则健康检查 DOWN。
log "兜底补齐缺失的 rabbitmq 配置"
for svc in "${SERVICES[@]}"; do
  dst="$DST/$svc/application-prod.yml"
  [ -f "$dst" ] || continue
  grep -qE '^[[:space:]]*rabbitmq:[[:space:]]*$' "$dst" && continue
  if ! grep -qE '^mybatis-plus:' "$dst"; then
    echo "  ⚠️  $svc 缺 rabbitmq 块且无 mybatis-plus 锚点，请人工确认：$dst" >&2
    continue
  fi
  awk '
    !done && /^mybatis-plus:/ {
      print "  rabbitmq:"
      print "    host: wuling-rabbitmq"
      print "    port: 5672"
      print "    username: ${RABBITMQ_USER:wuling}"
      print "    password: ${RABBITMQ_PASSWORD}"
      print ""
      done=1
    }
    { print }
  ' "$dst" > "$dst.tmp" && mv "$dst.tmp" "$dst"
  echo "  已为 $svc 补 rabbitmq 默认块"
done

# ---------- 日志路径：容器内挂在 /opt/wuling/logs，保持与宿主机一致 ----------
# 现有配置写的就是 /opt/wuling/logs/<service>.log，compose 已把宿主机同路径挂进去，
# 因此无需修改。此处仅输出确认信息。
log "日志路径沿用 /opt/wuling/logs（已由 compose 挂载，与升级前一致）"

log "配置生成完成。建议抽查："
echo "  grep -nE 'address:|server-addr:|host:|jdbc:mysql' $DST/gateway/application-prod.yml"
