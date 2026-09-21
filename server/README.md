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
   mvn spring-boot:run
   ```
3. 冒烟：
   ```bash
   # 后台登录
   curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"userName\":\"admin\",\"password\":\"Admin@123\"}"
   # 小程序门店
   curl http://localhost:8080/api/v1/app/stores
   # 小程序菜单
   curl http://localhost:8080/api/v1/app/menu
   ```

## 种子账号
- 后台：admin / Admin@123（BCrypt）

## 约定
- 成功码 `code=0`；金额 `BIGINT`（分）；分账比例 `INT`（万分比）。
- 统一响应 `{ code, message, data }`，分页 `{ records, current, size, total }`。

## 重新生成商品菜单 seed
```bash
node server/scripts/gen-product-seed.cjs
```
