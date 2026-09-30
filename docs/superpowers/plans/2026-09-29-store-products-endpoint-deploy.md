# 门店选品接口 404 修复与上线实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让小程序门店选品页 `GET /api/v1/app/workbench/store/{id}/products` 在线上可用，并保证「门店店主 = 服务端能识别的主体」这一数据前提被显式验证。

**Architecture:** 三处缺口修复 —— ① `server` 镜像需重建（含 `/internal/store-operator-check`，product-service 归属校验依赖）；② `product-service` 需改 `ProductSecurityConfig` 从管理端拦截器切到小程序拦截器，并重建镜像；③ `gateway` 路由需为 workbench 选品前缀补一条精确路由。三者必须全部落地，缺任一项都会失败（详见「失败组合矩阵」）。

**Tech Stack:** Spring Boot 3.3.5 / Spring Cloud Gateway / MyBatis-Plus；Docker Compose（`docker/docker-compose.prod.yml`，镜像 tag 驱动）；微信小程序原生。

**Spec:** 用户需求（本会话）：线上小程序点进「门店选品」报错，`.../store/102/products?current=1&size=1&listed=false` 返回 404；确认部署方式为 docker compose，选定「方案 A 正式上线」。

---

## 一、排查结论（已实测，非推测）

| 证据                                       | 命令/来源                                                                                                    | 结果                                                                                                       |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| 接口直连返回 401 而非 404                  | `Invoke-WebRequest http://43.136.91.239:8089/api/v1/app/workbench/store/102/products?...`                    | **401**（网关要求小程序 token）                                                                            |
| 同服务其它接口正常                         | `GET /api/v1/app/menu`                                                                                       | **200** `code=0`，17.6KB                                                                                   |
| 商品详情正常                               | `GET /api/v1/app/products/classic-001`                                                                       | **200**                                                                                                    |
| 线上 `product-service` jar 无该 Controller | 解包 `.deploy-staging/release-v48-20260926-172147/product-service/target/product-service-0.1.0-SNAPSHOT.jar` | `BOOT-INF/classes/com/wuling/product/controller/` 下**只有** `AdminProductController`、`AppMenuController` |
| 源码有该 Controller                        | `git log`                                                                                                    | 新增于 `c915967`，**2026-09-29 21:27**；部署产物为 **2026-09-26 17:21**                                    |
| 网关路由缺前缀                             | `gateway/src/main/resources/application.yml`                                                                 | `product-app` 仅 `Path=/api/v1/app/menu,/api/v1/app/products/**`                                           |

### 根因（三条同时成立）

1. **镜像滞后**：`product-service` 线上镜像构建于 09-26，不含 09-29 新增的 `AppStoreProductController` / `StoreProductService` / `ProductStoreMapper#selectListedProductIds` / `RemoteStoreOperatorAdapter`。
2. **网关路由未覆盖**：`/api/v1/app/workbench/store/*/products` 不匹配 `product-app`，被 `legacy-server`（`/api/v1/**` 兜底）抢走，而 server 无此类 → `NoResourceFoundException`。
3. **拦截器类型错配**：`ProductSecurityConfig` 只注册了 `AdminAuthInterceptor`（校验 `type=="access"`），而选品接口是小程序调用（`type=="mini"`）。**即使 1、2 修复，小程序请求仍会 401。**

### 前端 404 的真实来源（不要被误导）

`user-h5/config.js` 的 `PREVIEW_BASE_URL = ''http://43.136.91.239:8089''` 为 IP + 非标端口 + HTTP。微信 `wx.request` 合法域名只接受 HTTPS + 域名，请求在开发者工具层就被拦截并报 404 —— **该 404 不能用于判断后端接口是否存在**。

---

## 二、失败组合矩阵（为什么三处必须全改）

| server 新镜像 |    product 新镜像     | 网关新路由 | 结果                                                 |
| :-----------: | :-------------------: | :--------: | ---------------------------------------------------- |
|       ✗       |           ✗           |     ✗      | 429/500：请求落到 server，无该类（当前线上状态）     |
|       ✓       |           ✗           |     ✓      | 404：路由到 product-service，但该类不存在            |
|       ✓       |           ✓           |     ✗      | 500：仍落到 server，无该类                           |
|       ✓       |           ✓           |     ✓      | 404/500（拦截器仍是 Admin，小程序 token 被拒 → 401） |
|     **✓**     | **✓（含拦截器修复）** |   **✓**    | **200 `code=0`** ← 目标                              |

---

## 三、任务总览（严格按序执行）

1. Task 1：修复 `ProductSecurityConfig` 拦截器类型（源码，本地可验证）
2. Task 2：补齐网关 workbench 选品路由（源码）
3. Task 3：本地构建 + 单测验证
4. Task 4：服务器备份（镜像 tag + 配置 + compose）
5. Task 5：部署 server 并验证内部接口
6. Task 6：部署 product-service 并验证
7. Task 7：部署 gateway（新路由）并全量验证
8. Task 8：**数据前提校验**（门店 102 的经营者绑定）
9. Task 9：小程序端联调验证 + 文档留档

---

## Global Constraints

- **部署顺序不可颠倒**：product-service 的 `RemoteStoreOperatorAdapter` 通过 Nacos 调 `wuling-server` 的 `/internal/store-operator-check`。若 server 未先更新，选品接口虽能路由到 product-service，但归属校验会走 `RemoteStoreOperatorAdapter` 的 catch 分支 → 恒返回 `false` → **403「无权管理该门店选品」**（fail-closed，不会静默放行）。
- **网关路由必须放在 `legacy-server` 之前**：Spring Cloud Gateway 按定义顺序匹配，`legacy-server` 的 `/api/v1/**` 是兜底，任何新前缀都必须插入其上方。
- **`/internal/**` 不经网关\*\*，仅回环可达，不得为其新增路由。
- 采用 docker compose 部署：镜像 tag 驱动，`docker/deploy-backend.sh` 为入口；**不要** 直接 `docker build` 单服务后 `up`，否则 tag 与 `.last-image-tag` 不一致会导致回滚困难。
- 本计划**不改** `user-h5` 任何页面代码：选品页逻辑（`packageRole/role-products/`）已在 `c915967` 提交中就绪。
- 金额单位一律「分」；时间口径 `Asia/Shanghai`。

---

## Task 1：修复 `ProductSecurityConfig` 拦截器类型

**Files:**

- Modify: `product-service/src/main/java/com/wuling/product/config/ProductSecurityConfig.java`

- [ ] **Step 1：确认现状**

当前只注册管理端拦截器，且注释明确写着「不要用它保护 `/api/v1/app/**`」的同类约束（见 `security-common/.../AdminAuthInterceptor.java` 类注释）：

```java
registry.addInterceptor(adminAuthInterceptor)
        .addPathPatterns("/api/v1/admin/product/**");
// 小程序菜单 / 商品详情为公开数据，不加入拦截清单。
```

`AdminAuthInterceptor` 校验 `type == "access"`；小程序 token 的 `type == "mini"`，必然被拒。

- [ ] **Step 2：注入并注册小程序拦截器**

`MiniAppAuthInterceptor` 是 `security-common` 中的 `@Component`，product-service 的 `SecurityConfig` 已 `permitAll` 掉 `/api/v1/**`（外层放行，内层精确拦截），因此只需补路径清单。改用：

```java
package com.wuling.product.config;

import com.wuling.security.AdminAuthInterceptor;
import com.wuling.security.MiniAppAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 商品服务鉴权拦截器注册（第 13 期；门店选品修复）。
 *
 * <p>需登录的接口分两类，token 类型不同，必须用不同拦截器：
 * <ul>
 *   <li>管理端商品维护（{@code /api/v1/admin/product/**}）—— 运营后台 token（type=access）
 *       用 {@link AdminAuthInterceptor}；</li>
 *   <li>小程序门店选品（{@code /api/v1/app/workbench/store/*&#47;products} 系列）——
 *       小程序 token（type=mini）用 {@link MiniAppAuthInterceptor}。</li>
 * </ul>
 *
 * <p><b>修复记录（2026-09-29）</b>：选品接口上线时只挂了管理端拦截器，
 * 小程序调用因 type 不匹配被拒（401）；且 {@code CurrentUser} 从未写入，
 * 即使放行也会在 {@code requireStoreOperator} 处 NPE。现按 token 类型分挂。
 *
 * <p>公开：小程序菜单与商品详情（{@code /api/v1/app/menu}、
 * {@code /api/v1/app/products/**}）—— 属公开商品数据，与
 * {@code GatewayAuthPolicy} 的 PUBLIC 清单一致，不加入拦截清单。
 */
@Configuration
public class ProductSecurityConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final MiniAppAuthInterceptor miniAppAuthInterceptor;

    public ProductSecurityConfig(AdminAuthInterceptor adminAuthInterceptor,
                                 MiniAppAuthInterceptor miniAppAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.miniAppAuthInterceptor = miniAppAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/api/v1/admin/product/**");

        // 门店选品：读写都必须登录，且服务端按 JWT 校验门店归属（越权防护）
        registry.addInterceptor(miniAppAuthInterceptor)
                .addPathPatterns(
                        "/api/v1/app/workbench/store/*/products",
                        "/api/v1/app/workbench/store/*/products/**"
                );
        // 小程序菜单 / 商品详情为公开数据，不加入拦截清单。
    }
}
```

- [ ] **Step 3：与网关鉴权清单对齐**

确认 `gateway/src/main/java/com/wuling/gateway/security/GatewayAuthPolicy.java` 已把该前缀列为需登录（若不是，需同步补，否则网关会放行匿名请求到 product-service，虽然后者也会拦，但两层口径不一致）。检查其 PUBLIC 清单是否包含 `workbench/store/*/products`，**不应包含**。

- [ ] **Step 4：编译验证**

```powershell
mvn -q -pl product-service -am -DskipTests package
```

预期：BUILD SUCCESS。

---

## Task 2：补齐网关 workbench 选品路由

**Files:**

- Modify: `gateway/src/main/resources/application.yml`

- [ ] **Step 1：在 `product-app` 之后、`legacy-server` 之前插入新路由**

```yaml
# ---------- 商品服务（第 13 期拆分）----------
- id: product-admin
  uri: lb://wuling-product-service
  predicates:
    - Path=/api/v1/admin/product/**

# 商品服务 · 小程序菜单/详情（公开数据）
- id: product-app
  uri: lb://wuling-product-service
  predicates:
    - Path=/api/v1/app/menu,/api/v1/app/products/**

# 商品服务 · 门店选品（小程序，需登录）
# 为什么单独一条：选品控制器路径为
#   /api/v1/app/workbench/store/{id}/products
# 与 trade-service 的核销路径（/api/v1/app/workbench/store/{id}/verify*）
# 同处 workbench 前缀下，但归属不同服务，只能按精确子路径分流；
# 否则会被下方 legacy-server 的 /api/v1/** 兜底吞掉（server 无此类 -> 500）。
# 顺序敏感：必须排在 legacy-server 之前。
- id: product-app-workbench
  uri: lb://wuling-product-service
  predicates:
    - Path=/api/v1/app/workbench/store/*/products,/api/v1/app/workbench/store/*/products/**
```

- [ ] **Step 2：确认与 `trade-app-verify` 无冲突**

`trade-app-verify` 只匹配 `.../verify-records`、`.../verify-pool`、`.../verify`、`/store-operator/check`，与 `/products` 不重叠。两条路由顺序不影响正确性，但保持「product 相关紧邻 product-app」更易读。

- [ ] **Step 3：更新镜像内的网关配置源**

生产是容器化部署，网关配置来源为 `/opt/wuling/config/gateway/application-prod.yml`（由 `docker/prepare-config.sh` 从 `/opt/wuling/app/gateway/application-prod.yml` 生成，再挂载到容器 `/app/config`）。

⚠️ **关键**：`application.yml` 会打进 jar，但 `application-prod.yml` 在容器中以 `--spring.config.additional-location=file:/app/config/` **覆盖**。`spring.cloud.gateway.routes` 是**列表**，external 配置会整体覆盖 jar 内的列表 —— 因此必须同步修改 `application-prod.yml` 的 routes，**只改 jar 内 `application.yml` 不生效**。

---

## Task 3：本地构建 + 单测验证

- [ ] **Step 1：全量构建**

```powershell
mvn -q -DskipTests package
```

- [ ] **Step 2：跑选品单测（`c915967` 已补 `StoreProductServiceTest`）**

```powershell
mvn -q -pl product-service test -Dtest=StoreProductServiceTest
```

预期：全部通过。若 `selectBatchIds` 相关用例失败，参考 `docs/backend-p1-p2.md` 已知限制 2（`selectBatchIds` 在该 MP 版本的行为）改用 `selectList + in`。

- [ ] **Step 3：核对三个 jar 内确实含新类**

```powershell
# 期望看到 AppStoreProductController.class 与 StoreProductService.class
Add-Type -AssemblyName System.IO.Compression.FileSystem
$j = 'product-service/target/product-service-0.1.0-SNAPSHOT.jar'
$z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $j))
$z.Entries | Where-Object { $_.FullName -match 'product/(controller|service)/' } | Select-Object FullName
$z.Dispose()

# server 期望看到 InternalQueryController.class
$j = 'server/target/server-0.1.0-SNAPSHOT.jar'
$z = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $j))
$z.Entries | Where-Object { $_.FullName -match 'InternalQueryController' } | Select-Object FullName
$z.Dispose()
```

- [ ] **Step 4：提交源码改动**

```powershell
git add product-service/src/main/java/com/wuling/product/config/ProductSecurityConfig.java gateway/src/main/resources/application.yml
git commit -m "fix(product): 门店选品接口补齐小程序鉴权拦截器与网关路由"
```

---

## Task 4：服务器备份

**Files:** 服务器 `/opt/wuling/build/`、`/opt/wuling/app/`、`/opt/wuling/config/`

- [ ] **Step 1：记录当前镜像 tag（回滚锚点）**

```bash
STAMP=$(date +%Y%m%d-%H%M%S)
BK=/opt/wuling/backup/phase-store-products-$STAMP
mkdir -p "$BK"

# 当前在跑的 tag（compose 文件由 deploy-backend.sh 生成，或读 .last-image-tag）
cat /opt/wuling/build/.last-image-tag 2>/dev/null || true
docker inspect -f '{{.Config.Image}}' wuling-server wuling-product-service wuling-gateway

# 备份关键产物
cp /opt/wuling/config/gateway/application-prod.yml "$BK/gateway-application-prod.yml"
cp /opt/wuling/app/gateway/application-prod.yml     "$BK/gateway-application-prod.yml.host"
cp /opt/wuling/build/docker-compose.prod.yml        "$BK/docker-compose.prod.yml"
docker image save wuling/server:$(cat /opt/wuling/build/.last-image-tag)         -o "$BK/server-image.tar"
docker image save wuling/product-service:$(cat /opt/wuling/build/.last-image-tag) -o "$BK/product-service-image.tar"
docker image save wuling/gateway:$(cat /opt/wuling/build/.last-image-tag)         -o "$BK/gateway-image.tar"
echo "backup -> $BK"
```

> 若镜像体积过大不便落盘，至少保留 tag：`wuling/<svc>:<旧tag>` **不要**被 `clean-containers.sh` 清掉（`KEEP_VERSIONS=1` 仅保留最近 1 个版本，务必确认旧 tag 仍在 `docker images` 中）。

- [ ] **Step 2：确认中间件网络与 secrets 就绪**

```bash
docker network inspect wuling-net >/dev/null && echo "net OK"
ls -l /opt/wuling/app/secrets.env
```

---

## Task 5：部署 server 并验证

> **为什么先 server**：product-service 的选品接口在入口处调 `/internal/store-operator-check`；server 不先更新，该调用会失败，接口会以 403 结束（fail-closed）。

- [ ] **Step 1：上传新 jar 与 compose 到构建目录**

```bash
# 本地：打包三服务 jar + docker/ 目录
# 服务器 target：/opt/wuling/build/
#   server/target/server-0.1.0-SNAPSHOT.jar
#   product-service/target/product-service-0.1.0-SNAPSHOT.jar
#   target/gateway-0.1.0-SNAPSHOT.jar
#   docker/Dockerfile, docker/entrypoint.sh, docker-compose.prod.yml
```

- [ ] **Step 2：构建并滚动升级 server**

```bash
cd /opt/wuling/build
IMAGE_TAG=$(date +%Y%m%d-%H%M%S) IMAGE_TAG=$IMAGE_TAG ./../deploy/deploy-backend.sh build   # 或按实际路径
# 仅升级 server（避免无关服务重启）
IMAGE_TAG=$IMAGE_TAG docker compose -f /opt/wuling/build/docker-compose.prod.yml --project-name wuling up -d --no-build server
```

- [ ] **Step 3：验证内部接口存在（决定性）**

```bash
# 期望 {"allowed":false} 或 {"allowed":true}，而非 404/500
curl -sS "http://127.0.0.1:8090/internal/store-operator-check?userId=1&subjectId=102"
```

> **判读注意**（见 `docs/生产部署记录.md` 第 13 期 3.1 节）：本项目「接口不存在」可能表现为 **HTTP 200 + `code=500`**，不要只看状态码。对照实验：请求 `/internal/nonexistent-xyz`，若与上面响应**完全一致**，说明接口不存在。

- [ ] **Step 4：确认 server 健康**

```bash
curl -fsS http://127.0.0.1:8090/actuator/health
```

---

## Task 6：部署 product-service 并验证

- [ ] **Step 1：构建并升级 product-service**

```bash
IMAGE_TAG=$IMAGE_TAG docker compose -f /opt/wuling/build/docker-compose.prod.yml --project-name wuling up -d --no-build product-service
```

- [ ] **Step 2：确认镜像内类存在**

```bash
docker exec wuling-product-service sh -c 'ls /app/app.jar'
# 用 unzip 校验（镜像内可能无 unzip，改用宿主机 jar 校验，见 Task 3 Step 3）
```

- [ ] **Step 3：健康检查**

```bash
curl -fsS http://127.0.0.1:8091/actuator/health
```

- [ ] **Step 4：Nacos 注册确认**

```bash
# 期望能查到 wuling-product-service（容器名注册）
curl -sS "http://127.0.0.1:8848/nacos/v1/ns/catalog/services?pageNo=1&pageSize=50" | grep -o "wuling-product-service"
```

---

## Task 7：部署 gateway（新路由）并全量验证

- [ ] **Step 1：更新宿主与容器化网关配置**

```bash
# 1) 宿主机配置（prepare-config 的源）
vi /opt/wuling/app/gateway/application-prod.yml    # 加入 product-app-workbench 路由

# 2) 重新生成容器化配置
/opt/wuling/deploy/prepare-config.sh                # 或按实际路径

# 3) 校验生成结果确实含新路由
grep -A4 "product-app-workbench" /opt/wuling/config/gateway/application-prod.yml
```

- [ ] **Step 2：重启网关**

```bash
docker compose -f /opt/wuling/build/docker-compose.prod.yml --project-name wuling up -d --no-build gateway
# 或：docker restart wuling-gateway
sleep 20 && curl -fsS http://127.0.0.1:8080/actuator/health
```

- [ ] **Step 3：路由归属决定性验证（对照实验）**

```bash
# 未登录：期望 401（网关拦截），而不是 500/404
curl -sS -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8080/api/v1/app/workbench/store/102/products?current=1&size=1&listed=false'

# 对照：回归既有路径仍正常
curl -sS 'http://127.0.0.1:8080/api/v1/app/menu' | head -c 60
curl -sS -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8080/api/v1/app/workbench/store/101/verify-records'
```

> 若未登录返回 200 且 `code=8888`，属既有的「业务码式未登录」表现（见 `MiniAppAuthInterceptor` 返回 8888）；以响应体为准。

- [ ] **Step 4：带真实小程序 token 验证（决定性）**

用生产 `JWT_SECRET` 构造 `type=mini` 的合法 token（参考第 13 期 `docs/生产部署记录.md` 4.4 的做法）：

```bash
curl -sS -H "Authorization: Bearer $MINI_TOKEN" \
  'http://127.0.0.1:8080/api/v1/app/workbench/store/102/products?current=1&size=1&listed=false'
```

**期望**：`code=0` + 分页结构。三种典型失败及含义：

| 响应                       | 含义                                    |
| -------------------------- | --------------------------------------- |
| `401` + `无效的令牌`       | Task 1 未生效（拦截器类型仍错）         |
| `403 无权管理该门店选品`   | 拦截器 OK，但归属校验未通过 → 查 Task 8 |
| `404`/`500 服务器内部错误` | 路由未生效或 jar 未更新                 |

- [ ] **Step 5：管理端回归（确认 Task 1 未破坏原功能）**

```bash
curl -sS -o /dev/null -w '%{http_code}\n' 'http://127.0.0.1:8080/api/v1/admin/product/list'
# 期望 401（未登录被拦），带运营 token 时应 200
```

---

## Task 8：数据前提校验（门店 102 的经营者绑定）

> **本任务可能暴露第二个问题**：即便代码与路由全部修好，若当前登录用户的绑定主体不是 102，接口会返回 **403**，页面仍不可用。

**背景（源码事实）**：`/api/v1/app/roles/mine` 对**角色**去重（`roles.stream().anyMatch(item -> roleId.equals(item.get("roleId")))`，见 `AppRoleController#mine`），即**一个用户最多返回一个 `store` 角色条目**；前端 `getCurrentSubjectId()` 也只按 `roleId` 取单个 `subjectId`。因此**一个账号无法同时管理多家门店** —— 门店选品页只能操作 `/roles/mine` 里返回的那一家。

- [ ] **Step 1：查当前测试账号的绑定主体**

```sql
-- userId 换成本次测试的小程序用户 ID
SELECT id, business_role, bound_subject_id FROM app_user WHERE id = ? AND deleted = 0;

SELECT role_code, subject_id, status FROM user_role_grant
WHERE user_id = ? AND deleted = 0 ORDER BY id;

-- 确认 102 是什么主体
SELECT id, subject_type, name FROM biz_subject WHERE id = 102;
```

- [ ] **Step 2：按结果分支处理**

| 情况                     | 处理                                                                                                                                                      |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 绑定/授权到 102          | 无需处理，直接进 Task 9                                                                                                                                   |
| 绑定到别的门店（如 101） | **产品决策**：是「该账号本就该管 101，页面显示 102 是前端 bug」还是「需要支持多门店」。前者改前端取数，后者要改后端去重逻辑（超出本计划范围，需另立计划） |
| 完全没有门店角色         | 走 `role-apply` 申请 → 运营后台审核 → 写入 `user_role_grant` + `app_user`                                                                                 |

- [ ] **Step 3：记录结论**

把结论补进 `docs/生产部署记录.md` 本次小节，避免下次重复排查。

---

## Task 9：小程序端联调验证 + 文档留档

- [ ] **Step 1：开发者工具联调**

`user-h5/config.js` 的 `PREVIEW_BASE_URL` 是 HTTP + IP + 非标端口，**微信开发者工具必须勾选「不校验合法域名」**，否则请求在本地被拦（就是这次误判成 404 的原因）。

- [ ] **Step 2：逐项验证选品页**

| 场景                    | 期望                                        |
| ----------------------- | ------------------------------------------- |
| 进入「门店选品」        | 标题 `{门店名}选品`，Tab 计数正常（非全 0） |
| 切「已上架」/「已下架」 | 分页正确，`total` 与筛选一致                |
| 搜索关键词              | 命中名称或编号，`total` 跟随变化            |
| 单条上架/下架           | 提示成功，切 Tab 后状态一致                 |
| 批量上架/下架           | 返回 `affected` 数量正确                    |
| 点单页回归              | 下架后菜单中该商品消失（缓存失效生效）      |

- [ ] **Step 3：小程序静态检查（项目约定强制）**

```powershell
cd user-h5
npm run check
```

- [ ] **Step 4：留档**

在 `docs/生产部署记录.md` 追加本次小节，包含：镜像 tag、备份目录、验证结果表、失败组合矩阵、以及 Task 8 的数据结论。

- [ ] **Step 5：提交**

```powershell
git add docs/生产部署记录.md
git commit -m "docs: 门店选品接口上线记录（含鉴权拦截器与网关路由修复）"
```

---

## 四、回滚方案

```bash
BK=/opt/wuling/backup/phase-store-products-<STAMP>
OLD_TAG=<旧镜像 tag>

# 1) 网关回滚（最快，先恢复入口）
cp $BK/gateway-application-prod.yml /opt/wuling/config/gateway/application-prod.yml
docker compose -f /opt/wuling/build/docker-compose.prod.yml --project-name wuling up -d --no-build gateway

# 2) 应用回滚
IMAGE_TAG=$OLD_TAG docker compose -f /opt/wuling/build/docker-compose.prod.yml --project-name wuling up -d --no-build server product-service

# 3) 校验
curl -fsS http://127.0.0.1:8080/actuator/health
```

> 回滚后门店选品**不可用**（回到本文档描述的初始状态），但不影响点单、核销、支付等既有链路 —— 因为本次改动仅新增路由与新增拦截清单，未修改既有逻辑。

---

## 五、剩余风险与待办

| 优先级 | 项                                             | 说明                                                                                                 |
| ------ | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| **P0** | 门店选品接口归属校验未做端到端验证             | Task 7 Step 4 只验到 403 分界；需用**真实门店账号**跑通全链路                                        |
| **P1** | `/roles/mine` 角色去重导致单账号仅能管一家门店 | Task 8 可能触发；若需多门店需另立计划                                                                |
| P1     | 选品列表全表 `selectList` 后内存分页           | `StoreProductService#pageStoreProducts` 拉全部在售商品再 `subList`，商品量增长后需改真分页（`Page`） |
| P2     | 前端预览基址是真机不可用                       | HTTPS 证书就绪后用 `PRODUCTION_BASE_URL`，否则真机测不了                                             |
| P2     | 网关 PUBLIC 清单与各服务拦截器清单可能不同步   | 项目已有 `AuthPolicyConsistencyTest`，新增路径应纳入                                                 |

---

## 六、验收标准

- [ ] 线上 `GET /api/v1/app/workbench/store/{id}/products?current=1&size=1&listed=false` 携带合法小程序 token 返回 `code=0`
- [ ] 未登录返回 401/8888（而非 500/404）
- [ ] 管理端 `/api/v1/admin/product/**` 带运营 token 仍 200（未被 Task 1 破坏）
- [ ] 门店核销 `/api/v1/app/workbench/store/*/verify-*` 仍正常（未被新路由抢走）
- [ ] 小程序选品页的上下架操作对点单页实时生效
- [ ] `cd user-h5 && npm run check` 通过
