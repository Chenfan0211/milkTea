# 五零时光 · 服务器安全加固脚本

> 目的：按「先检查、后执行、再验证」的顺序，加固生产服务器，
> 确保系统不崩溃（防 OOM / 防雪崩）且不中勒索病毒（防暴露 / 防入侵 / 可恢复）。
>
> **所有脚本只从环境变量读取口令，绝不硬编码、不回显明文。**

---

## 快速开始

```bash
# 0) 登录服务器
ssh root@<服务器IP>
cd /opt/wuling/app        # 或项目仓库所在目录

# 1) 先跑「只读检查」（无任何副作用），把输出贴回分析
bash deploy/security/run.sh --check
```

确认输出后，再按需执行加固。

---

## 脚本清单

| 脚本                         | 对应项      | 作用                                   | 有副作用？                  |
| ---------------------------- | ----------- | -------------------------------------- | --------------------------- |
| `run.sh`                     | 总入口      | 一键按顺序跑检查 / 加固                | `--check` 无；`--harden` 有 |
| `01-check.sh`                | 综合体检    | 只读检查全部安全项                     | ❌ 无                       |
| `02-redis-maxmemory.sh`      | P1-1        | Redis 内存上限 + 淘汰策略              | `--apply` 才改              |
| `03-middleware-hardening.sh` | P2-1 / P2-3 | 中间件暴露面 + 口令强度 + SSH 建议     | ❌ 无                       |
| `04-backup.sh`               | P2-2        | MySQL/Redis 备份 + 异地同步 + 恢复演练 | `--run` 才备份              |
| `05-nacos-auth.sh`           | P1-2        | Nacos 鉴权检查 + 加固步骤              | ❌ 无（只打印步骤）         |

---

## 按风险顺序执行

### 第一步：P2-1 中间件暴露面（勒索最直接入口）

```bash
bash deploy/security/03-middleware-hardening.sh
```

**要看的点**：

- 中间件端口是否只绑定 `127.0.0.1`（不应出现 `0.0.0.0`）
- 云安全组是否只放行 `80 / 443 / 22`，**不放行** 3306/6379/5672/8848
- 各口令长度是否 `>= 16`

### 第二步：P2-2 备份 + 恢复演练（最后兜底）

```bash
# 立即备份一次
bash deploy/security/04-backup.sh --run

# 安装每日 03:00 定时备份
bash deploy/security/04-backup.sh --install

# 恢复演练（验证备份真的可用）—— 必做
bash deploy/security/04-backup.sh --restore-test /opt/wuling/backup/mysql/wuling_xxx.sql.gz
```

**异地备份（关键，勿省略）**：

```bash
export BACKUP_REMOTE='backup@<另一台机器IP>:/data/wuling-backup/'
# 写入 crontab 或 secrets.env 使其持久生效
```

> ⚠️ 同机备份会被勒索一并加密，**必须**异地/离线保存。
> ⚠️ 未做过恢复演练的备份 = 没有备份。

### 第三步：P1-1 Redis 内存上限（防 OOM 崩溃）

```bash
bash deploy/security/02-redis-maxmemory.sh          # 检查
bash deploy/security/02-redis-maxmemory.sh --apply  # 执行
bash deploy/security/02-redis-maxmemory.sh --verify # 验证
```

默认设置：`maxmemory=512mb`、`maxmemory-policy=allkeys-lru`。
可用环境变量覆盖：`REDIS_MAXMEMORY=1gb REDIS_MAXMEMORY_POLICY=allkeys-lru`。

> 本项目所有 Redis 写入都带 TTL（验证码/幂等键/配置缓存），`allkeys-lru` 安全。

### 第四步：P1-2 Nacos 鉴权 + P2-3 SSH 加固

```bash
bash deploy/security/05-nacos-auth.sh --check   # 检查
bash deploy/security/05-nacos-auth.sh --apply   # 查看加固步骤（人工确认后执行）

bash deploy/security/03-middleware-hardening.sh --ssh-fix   # SSH 加固建议
```

---

## 口令来源

各脚本支持从密钥文件自动加载（不打印明文）：

```bash
set -a; . /opt/wuling/app/secrets.env; set +a
```

或由 `run.sh` 自动加载（路径可用 `SECRETS_FILE` 覆盖）。

需要的变量：`REDIS_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`、`RABBITMQ_PASSWORD`。

---

## 可配置环境变量

| 变量                     | 默认值               | 说明                             |
| ------------------------ | -------------------- | -------------------------------- |
| `DEPLOY_DIR`             | `/opt/wuling/deploy` | 中间件 compose 目录              |
| `BACKUP_DIR`             | `/opt/wuling/backup` | 备份输出目录                     |
| `BACKUP_REMOTE`          | 空                   | 异地同步目标（**强烈建议配置**） |
| `BACKUP_KEEP_DAYS`       | `30`                 | 备份保留天数                     |
| `MYSQL_CONTAINER`        | `wuling-mysql`       | MySQL 容器名                     |
| `REDIS_CONTAINER`        | `wuling-redis`       | Redis 容器名                     |
| `REDIS_MAXMEMORY`        | `512mb`              | Redis 内存上限                   |
| `REDIS_MAXMEMORY_POLICY` | `allkeys-lru`        | Redis 淘汰策略                   |

---

## 安全说明

1. **不硬编码口令**：所有脚本从环境变量取值，不回显明文（只输出长度）。
2. **默认只读**：除显式 `--apply` / `--run` / `--install` 外，不修改任何配置。
3. **不自动重启**：需要重启的操作（如 Nacos 鉴权）只打印步骤，由人工在低峰期确认执行。
4. **恢复演练安全**：演练建独立临时库，用完立即删除，**不触碰生产数据**。

---

## 配套的代码层加固（已完成）

以下为应用层的 P0 加固，已随代码提交，与本文档配合生效：

- 网关全局限流（`RequestRateLimiter`，按 IP 令牌桶）
- 网关熔断降级（Resilience4j `CircuitBreaker` + 统一 503 兜底）
- 各服务 HikariCP 显式配置（`connection-timeout: 3000` 防连接池耗尽雪崩）

---

## 实施记录（2026-09-28 已完成）

> 本次在源机 `43.136.91.239` + 异地备份机 `119.91.111.132` 上实际执行，全部验证通过。

### 已完成清单

| #   | 项                                     | 结果                            |
| --- | -------------------------------------- | ------------------------------- |
| 1   | Redis maxmemory（512MB + allkeys-lru） | ✅ 防 OOM                       |
| 2   | 异地备份同步（119.91.111.132）         | ✅ 每日 04:00                   |
| 3   | SSH 禁密码登录（源机+备份机）          | ✅ 仅密钥                       |
| 4   | Nacos 鉴权 + 强密码                    | ✅ 匿名 403                     |
| 5   | Redis 双网络固化                       | ✅ 重建不丢网络                 |
| 6   | Redis 危险命令禁用                     | ✅ FLUSHALL/FLUSHDB/CONFIG/KEYS |
| 7   | 密码外置（compose 无明文）             | ✅ secrets.env 唯一来源         |

### 恢复演练（已验证）

- 备份：`wuling_20260928_033001.sql.gz`（异地机）
- 还原到临时库：**68 张表**，`product=30`、`orders=37`、`app_user=4`、`flyway=61`
- 结论：✅ 异地备份可恢复

### 关键说明

1. **密码来源**：所有密码在 `/opt/wuling/app/secrets.env`（权限 600），compose 通过 `env_file` 引用，不再硬编码。
2. **Redis maxmemory 已持久化**：写在 compose 的 `command` 参数里，重建不丢（`CONFIG` 命令已禁用，不要再用 `02-redis-maxmemory.sh --apply` 在线改）。
3. **Nacos 客户端认证**：8 个服务的 `application-prod.yml` 已写入 Nacos 新密码；改 Nacos 密码需同步更新这 8 处。
4. **Redis 网络**：`wuling-net` 和 `deploy_default` 都声明为 external，Redis 重建会自动接入双网络。

### 服务器上实际新增/修改的文件

| 文件                                        | 说明                                                    |
| ------------------------------------------- | ------------------------------------------------------- |
| `/opt/wuling/deploy/docker-compose.yml`     | 密码外置 + env_file + Redis maxmemory/危险命令 + 双网络 |
| `/opt/wuling/app/secrets.env`               | 补 MYSQL_ROOT_PASSWORD                                  |
| `/opt/wuling/app/nacos-*.txt`               | Nacos token/identity/密码                               |
| `/opt/wuling/scripts/sync-backup-remote.sh` | 异地同步脚本                                            |
| `/opt/wuling/scripts/rebuild-nacos.sh`      | Nacos 重建脚本                                          |
| `/etc/cron.d/wuling-backup-remote`          | 异地备份定时任务                                        |
| `/opt/wuling/OPS.md`                        | 运维手册（已更新）                                      |
| 源机+备份机 `/etc/ssh/sshd_config`          | 禁密码登录                                              |
