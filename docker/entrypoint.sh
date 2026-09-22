#!/bin/sh
# =============================================================
# 后端服务统一启动脚本
#
# 为什么用脚本而不是直接 ENTRYPOINT java -jar：
#   1. 需要把 JAVA_OPTS（-Xms/-Xmx/编码/OOM 退出）与 profile 参数拼起来，
#      exec 形式无法做变量展开；
#   2. 需要统一 --spring.profiles.active 与 --spring.config.additional-location，
#      避免每个服务写一遍、写错一个就启动失败；
#   3. 用 exec 启动 Java 进程，使其成为 PID 1，
#      这样 docker stop 的 SIGTERM 能直接送达 JVM，实现优雅停机
#      （Spring Boot 会处理 SIGTERM 完成优雅关闭）。
# =============================================================
set -e

: "${JAVA_OPTS:=-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -XX:+ExitOnOutOfMemoryError}"
: "${SPRING_PROFILES_ACTIVE:=prod}"
: "${CONFIG_DIR:=/app/config}"

echo "[entrypoint] service=${SERVICE} profile=${SPRING_PROFILES_ACTIVE} java_opts=${JAVA_OPTS}"

exec java ${JAVA_OPTS} \
  -jar /app/app.jar \
  --spring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
  --spring.config.additional-location="file:${CONFIG_DIR}/"
