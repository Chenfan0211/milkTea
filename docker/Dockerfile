# =============================================================
# 五零时光 · 后端服务统一运行镜像（多阶段构建）
#
# 设计要点：
#   1. 上下文为「项目根目录」，一份 Dockerfile 覆盖 8 个服务，
#      用 SERVICE 构建参数选择要运行哪个 jar，避免维护 8 份近乎相同的文件；
#   2. jar 在【宿主机】用 Maven 构建后 COPY 进镜像，不在镜像内构建 ——
#      镜像内构建需要联网下载依赖，慢且不可复现（本项目依赖走内网镜像源）；
#   3. 运行时用 JRE（jre-noble）而非 JDK，镜像更小、攻击面更小；
#   4. 非 root 用户运行（wuling:10001），与「最小权限」原则一致。
#
# 构建示例：
#   docker build -f docker/Dockerfile --build-arg SERVICE=gateway \
#                --build-arg APP_JAR=target/gateway-0.1.0-SNAPSHOT.jar -t wuling/gateway:local .
# =============================================================

FROM eclipse-temurin:17-jre-noble

# 项目版本（仅用于打标签与排查，不参与运行逻辑）
ARG APP_VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.title="wuling-backend" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.description="五零时光后端服务"

# SERVICE：gateway / auth-service / ... 用于日志目录与标识
ARG SERVICE
ENV SERVICE=${SERVICE}

# 时区：与数据库、业务时间口径保持一致（项目统一 Asia/Shanghai）
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# curl 用于健康检查（compose 的 healthcheck 依赖它）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 运行。
#
# ⚠️ 为什么直接复用基础镜像自带的 ubuntu(1000:1000) 而不新建用户：
#    1. 日志必须落到宿主机 /opt/wuling/logs，容器内进程需对该目录有写权限。
#       首轮用自造的 uid 10001 时，logback 因无权限而【静默失败】
#       （不报错、只是不写文件），排查成本极高；
#    2. jre-noble 基础镜像已存在 ubuntu 用户且正好是 1000:1000，
#       复用它即可与宿主机目录属主天然对齐，无需额外建组建户；
#    3. 新建 1000 组会与已有 ubuntu 组冲突（groupadd 报 exit 4），
#       这是首轮实测踩到的第二个坑。
#
# 宿主机侧对应操作：chown -R 1000:1000 /opt/wuling/logs
# （由 deploy-backend.sh 的 preflight / fix-logging 自动完成）
#
# 注意：USER 必须放在所有需要 root 权限的 RUN 之后（COPY/chmod/chown），
#       否则那些指令会以 ubuntu 身份执行而失败。见文件末尾。

WORKDIR /app

# 启动脚本（负责把 JAVA_OPTS 与 profile 参数拼进命令行）
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# 应用 jar：名字固定为 app.jar，entrypoint 无需感知具体版本号
ARG APP_JAR
COPY ${APP_JAR} /app/app.jar

# 日志目录：由 compose 挂载到宿主机，容器内只是占位。
# ⚠️ 必须在切换到非 root 用户【之前】创建并授权 —— 否则 mkdir /opt/wuling 会因
#    普通用户对 /opt 无写权限而失败（这是首轮构建实际踩到的坑）。
RUN mkdir -p /opt/wuling/logs /data/wuling/uploads \
    && chown -R ubuntu:ubuntu /app /opt/wuling /data/wuling

# 切换到非 root 用户（必须位于所有 root 操作之后）
USER ubuntu

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
