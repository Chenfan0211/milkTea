# 五零时光 · 服务器环境与本地联调说明（第一批）

> **凭据说明**：本文档中的服务器地址与各服务口令已脱敏为占位符，
> 真实值请从密码管理渠道获取，**不要回填到本文件**。
> 本地联调所需的环境变量见 `deploy/.env.example`。

> 服务器：`<服务器IP>`（CentOS 7.9 / 8 核 / 14G / 99G）
> 状态：Docker 26.1.4 + Compose v2.27.1 已装；MySQL 8.0.46 / Redis 7 / RabbitMQ 3 已运行
> 安全策略：数据库与中间件**只监听 127.0.0.1**，不暴露公网，本地通过 SSH 隧道访问

## 一、服务器上的中间件

部署目录：`/opt/wuling/deploy/docker-compose.yml`

| 服务 | 容器名 | 宿主机绑定 | 账号 |
|------|--------|-----------|------|
| MySQL 8.0.46 | wuling-mysql | 127.0.0.1:3306 | root/`<MYSQL_ROOT_PASSWORD>`，业务库 wuling/wuling |
| Redis 7 | wuling-redis | 127.0.0.1:6379 | 密码 `<REDIS_PASSWORD>` |
| RabbitMQ 3 | wuling-rabbitmq | 127.0.0.1:5672 / 15672 | wuling/`<RABBITMQ_PASSWORD>` |

常用命令（服务器上执行）：

```bash
cd /opt/wuling/deploy
docker compose ps
docker compose logs -f mysql
docker compose restart redis
docker exec -it wuling-mysql mysql -uroot -p wuling   # 密码见密码管理
```

数据卷：`deploy_mysql-data`、`deploy_redis-data`、`deploy_rabbitmq-data`（位于 `/var/lib/docker/volumes/`）

## 二、本地 SSH 隧道（本地联调必做）

服务器已安装本机公钥，可免密登录。建立隧道：

```powershell
ssh -N -L 13306:127.0.0.1:3306 -L 16379:127.0.0.1:6379 -L 15672:127.0.0.1:5672 root@<服务器IP>
```

映射关系：

| 本地端口 | 服务器端口 | 用途 |
|---------|-----------|------|
| 13306 | 3306 | MySQL |
| 16379 | 6379 | Redis |
| 15672 | 5672 | RabbitMQ AMQP |

> 注：本地 15672 复用为 RabbitMQ AMQP，管理台未映射到本地（如需可另加 `-L 25672:127.0.0.1:15672`）。

## 三、启动后端

`server/src/main/resources/application-dev.yml` 已指向隧道端口（MySQL 13306 / Redis 16379 / RabbitMQ 15672）。

```powershell
cd <项目根目录>/server
mvn -DskipTests package
java -jar target/server-0.1.0-SNAPSHOT.jar
```

Flyway 会在首次启动时自动建表并写入种子数据。

> 注意：中文路径下 `mvn spring-boot:run` 的 fork 环节会报 `ClassNotFoundException`，请统一用 `java -jar` 方式启动。

## 四、冒烟验证

```powershell
# 健康检查
curl http://127.0.0.1:8080/actuator/health

# 后台登录（账号见密码管理）
curl -X POST http://127.0.0.1:8080/auth/login -H "Content-Type: application/json" -d "{\"userName\":\"<账号>\",\"password\":\"<密码>\"}"

# 小程序公开接口
curl http://127.0.0.1:8080/api/v1/app/store-types
curl http://127.0.0.1:8080/api/v1/app/stores
curl http://127.0.0.1:8080/api/v1/app/menu
curl http://127.0.0.1:8080/api/v1/app/products/classic-001
```

## 五、数据库现状（已初始化）

- 表数量：**58**
- Flyway 版本：V1 schema_core / V2 schema_marketing / V3 seed_base / V4 seed_product
- 种子数据：门店类型 4、门店 5、商品 18、规格 106、商品-门店关联 90、会员等级 3、功能开关 4、分账规则 1

## 六、待办（后续批次）

1. 生产环境变量与密钥注入（JWT secret、数据库口令改为强口令）
2. Nginx + HTTPS + 域名（api.wulingshiguang.top）
3. ICP 备案 / 微信小程序企业主体认证
4. 业务接口开发：订单、支付（适配层+Mock）、核销、五方分账、提现、营销
5. 监控告警、数据库每日备份
