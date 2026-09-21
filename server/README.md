# 五零时光后端（server）

第一批：框架 + 全量表结构 + 数据初始化 + 样板接口。

## 技术栈
Java 17（本机 JDK 18 以 release 17 编译）、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、MySQL 8、Redis 7、RabbitMQ 3、Flyway、jjwt。

## 本地启动
1. 启动中间件（需 Docker Desktop）：
   ```bash
   docker compose -f deploy/dev/docker-compose.yml up -d
   ```
2. 启动后端（Flyway 会自动建表 + 初始化种子数据）：
   ```bash
   cd server
   mvn -DskipTests package
   java -jar target/server-0.1.0-SNAPSHOT.jar
   ```
   > 中文路径下 `mvn spring-boot:run` 的 fork 环节会报 `ClassNotFoundException`，请统一用 `java -jar` 启动。
3. 冒烟：
   ```bash
   # 后台登录
   curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"userName\":\"<账号>\",\"password\":\"<密码>\"}"
   # 小程序门店
   curl http://localhost:8080/api/v1/app/stores
   # 小程序菜单
   curl http://localhost:8080/api/v1/app/menu
   ```

## 种子账号
- 后台初始账号：`admin`（密码为 BCrypt 哈希，初始密码见部署文档，首次登录后请立即修改）

## 约定
- 成功码 `code=0`；金额 `BIGINT`（分）；分账比例 `INT`（万分比）。
- 统一响应 `{ code, message, data }`，分页 `{ records, current, size, total }`。

## 重新生成商品菜单 seed
```bash
node server/scripts/gen-product-seed.cjs
```
