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

## 六、生产文件服务持久化（必须配置）

当前 `deploy/dev/docker-compose.yml` 只包含 MySQL、Redis、RabbitMQ、Nacos，不包含
`file-service`。生产 Compose 或编排模板中必须为文件服务配置持久化卷；不能只把上传目录
放在容器可写层，否则容器重建、升级或迁移后会丢失图片。

生产环境至少应设置：

- `FILE_STORAGE_ROOT=/data/wuling/uploads`
- `FILE_PUBLIC_BASE_URL=/api/v1/files/public`
- `JWT_SECRET`：使用生产密钥管理注入，不能使用开发默认值

具名卷示例：

```yaml
services:
  file-service:
    image: <file-service-image>
    restart: unless-stopped
    environment:
      FILE_STORAGE_ROOT: /data/wuling/uploads
      FILE_PUBLIC_BASE_URL: /api/v1/files/public
      JWT_SECRET: ${JWT_SECRET:?required}
    volumes:
      - file-uploads:/data/wuling/uploads

volumes:
  file-uploads: {}
```

若需直接挂载宿主机目录，可将卷改为：

```yaml
    volumes:
      - /srv/wuling/uploads:/data/wuling/uploads
```

两种方式都必须保证该目录只属于文件服务、随宿主机或具名卷持久化，并纳入数据库之外的
文件备份与恢复流程。部署后应分别验证管理员上传、公开读取，以及重启容器后文件仍可读取。

## 七、礼品卡真实支付生产配置（必须配置）

礼品卡正式购买与退款使用微信小程序 JSAPI。生产环境必须将
`PAY_CHANNEL=wxpay`；`PAY_CHANNEL=mock` 只允许用于开发、自动化测试和预发验收，
不能作为正式收款通道。

`secrets.env` 至少需要补齐：

- `PAY_CHANNEL=wxpay`
- `PAY_CALLBACK_SECRET`：内部支付回调签名密钥
- `WXPAY_MCH_ID`、`WXPAY_APP_ID`、`WXPAY_API_V3_KEY`
- `WXPAY_MCH_SERIAL_NO`、`WXPAY_PRIVATE_KEY_PATH`
- `WXPAY_VERIFY_MODE=public-key`、`WXPAY_PUBLIC_KEY_ID`、`WXPAY_PUBLIC_KEY_PATH`
- `WXPAY_NOTIFY_URL=https://api.wulingshiguang.top/api/v1/app/payments/wxpay/notify`
- `WXPAY_REFUND_NOTIFY_URL=https://api.wulingshiguang.top/api/v1/app/payments/wxpay/refund-notify`
- `INTERNAL_MARKETING_BASE=http://wuling-marketing-service`
- `INTERNAL_SERVICE_TOKEN`：`trade-service` 与 `marketing-service` 必须使用完全相同的值
- `STORED_VALUE_DEMO_ENABLED=false`

微信商户证书目录需以只读方式挂载给 `trade-service`，例如：

```yaml
    volumes:
      - /opt/wuling/app/certs:/opt/wuling/app/certs:ro
```

容器内两个 `WXPAY_*_KEY_PATH` 必须落在该挂载目录内且运行用户可读。生产 Nginx
必须将两个回调地址通过 HTTPS 暴露给微信，证书域名和 ICP 备案状态需与商户平台一致。
部署后先验证配置缺失时服务拒绝启动，再完成一笔真实购买和原路退款，并核对
`gift_card_order`、`gift_card`、`gift_card_refund` 与 `payment` 四张表的状态证据。

## 八、待办（后续批次）

1. 生产环境变量与密钥注入（JWT secret、数据库口令改为强口令）
2. Nginx + HTTPS + 域名（api.wulingshiguang.top）
3. ICP 备案 / 微信小程序企业主体认证
4. 业务接口开发：订单、支付（适配层+Mock）、核销、五方分账、提现、营销
5. 监控告警、数据库每日备份
