# 运维备忘：后台网关 CORS 配置变更

> 日期：2026-10-10
> 触发场景：后台登录页（`http://43.136.91.239:8088`）请求 `http://43.136.91.239:8089` 接口全部报 CORS error，快捷登录入口被隐藏。

## 一、问题根因

| 项目     | 说明                                                                                                                                        |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| 报错接口 | `/auth/login`、`/route/getConstantRoutes`、`/auth/quick-login/enabled`                                                                      |
| 报错现象 | 浏览器全部显示 CORS error                                                                                                                   |
| 链路     | 页面(8088) → nginx(8089) → gateway(8080) → 下游服务                                                                                         |
| 根因     | 远程 gateway 的 CORS `allowedHeaders` 白名单只列了 3 个头，且 origin 白名单缺本地开发入口；叠加浏览器缓存了失败的预检响应（`maxAge: 3600`） |

## 二、关键结论

1. **生效配置在远程，不在本地仓库**：gateway 以 `prod` profile + 外部挂载目录启动，实际读取的是 `/opt/wuling/config/gateway/application-prod.yml`，而仓库里只有 `application.yml` / `application-dev.yml`，改本地仓库无效。

2. **配置链路**（gateway 容器启动参数）：

   ```
   --spring.profiles.active=prod
   --spring.config.additional-location=file:/app/config/
   ```

   `/app/config` 挂载自宿主机 `/opt/wuling/config/gateway/`，由 `docker/prepare-config.sh` 从 `/opt/wuling/app/gateway/application-prod.yml` 复制生成。

3. **排查方法**（curl 分级定位）：
   - 直接测 gateway：`curl -i -X OPTIONS http://127.0.0.1:8080/...` → 有 CORS 头说明 gateway 正常
   - 测 nginx 入口：`curl -i -X OPTIONS http://127.0.0.1:8089/...` → 对比是否丢失 CORS 头
   - 模拟真实 POST + 完整 `Access-Control-Request-Headers` 验证预检头是否齐全

## 三、本次实际改动

**文件**：`/opt/wuling/config/gateway/application-prod.yml`

1. `allowedHeaders` 由三项列表改为通配：

   ```yaml
   # 改前
   allowedHeaders:
     - Authorization
     - Content-Type
     - apifoxToken
   # 改后
   allowedHeaders: "*"
   ```

2. `allowedOriginPatterns` 补充本地开发 origin：

   ```yaml
   allowedOriginPatterns:
     - 'https://*.wulingshiguang.top'
     - 'https://wulingshiguang.top'
     - 'http://43.136.91.239:*'
     - 'http://localhost:*' # 新增
     - 'http://127.0.0.1:*' # 新增
   ```

3. 重启：`docker restart wuling-gateway`

> 说明：`allowedHeaders: "*"` 与 `allowCredentials: true` 组合在 Spring 中是合法的（Spring 会自动回显实际请求头），与 origin 的 `*` 冲突规则不同。

## 四、后续注意

- **重新部署 gateway 会覆盖远程配置**：远程 `application-prod.yml` 是手动维护、不在仓库里的。若重新构建部署，需把 `allowedHeaders: "*"` 与 origin 白名单同步回 `/opt/wuling/config/gateway/application-prod.yml`（以及源头 `/opt/wuling/app/gateway/application-prod.yml`）。

- **浏览器预检缓存**：`maxAge: 3600` 会让浏览器缓存 OPTIONS 结果 1 小时。改了后端 CORS 后若仍报错，先清浏览器缓存（DevTools → Disable cache + Ctrl+Shift+R，或用无痕窗口）。

- **备份文件**：本次改动前已备份为 `/opt/wuling/config/gateway/application-prod.yml.bak.`（历史还有多个 `.bak-*` 文件，回滚可参考）。

---

# 运维备忘：后台 server 服务 Flyway checksum 漂移修复

> 日期：2026-10-10
> 触发场景：为放行 `/route/getConstantRoutes`（方案 A）重新构建 server 镜像并部署后，容器启动失败，报 `Migration checksum mismatch for migration version 70`，一直 `Restarting (1)`。

## 一、问题现象

```
FlywayValidateException: Validate failed: Migrations have failed validation
Migration checksum mismatch for migration version 70
-> Applied to database : 853398599
-> Resolved locally    : -219537893
```

## 二、根因

1. **数据库 `flyway_schema_history` 里 V70 存的 checksum = `853398599`**，是「含换行符的整文件 CRC32」。
2. **Flyway 10.10 的 checksum 算法是「逐行 `readLine()` 去换行符、去 BOM 后再 CRC32」**，对同一份 V70 文件算出的值是 `-219537893`（有符号 int32）。
3. 两者不同，是 **Flyway 版本演进导致的算法差异**（历史上生产数据库用旧算法写入 checksum）。
4. **旧镜像之所以能正常启动**：旧镜像打包时 V70 迁移文件还不存在，Flyway 启动时不校验 V70，因此「恰好」没暴露这个漂移。新镜像带上了 V70 文件，校验时才触发。

> 注意：`853398599` 与 `-219537893` 不是同一个数（不是 signed/unsigned 的简单换算）。`-219537893` 是 `853398599` 的补码再减 2^32？不对——它们本质是「不同算法」的结果，不要误判为同一值。

## 三、修复方式

**等价于 Flyway 官方 `repair` 的 checksum 更新**（只改元数据，不执行任何迁移 SQL）：

```sql
UPDATE flyway_schema_history SET checksum = -219537893 WHERE version = '70';
```

修复前已备份表：

```sql
CREATE TABLE flyway_schema_history_bak_20261010 AS SELECT * FROM flyway_schema_history;
```

修复后校验通过，server 应用正常启动。

## 四、Flyway 10.10 checksum 算法（复现方法）

Flyway 10.10 的 `ChecksumCalculator.calculateChecksumForResource` 核心逻辑：

1. `BufferedReader.readLine()` 逐行读取（**剥离换行符**）
2. 每行 `BomFilter.FilterBomFromString`（去行首 U+FEFF）
3. 每行 `String.getBytes(UTF_8)` 累加到 `java.util.zip.CRC32`
4. `crc32.getValue()` 转 `int`（有符号）

用 Python 复现：

```python
import zlib
data = open('V70__fix_home_menu_name_and_points_rule_icon.sql', encoding='utf-8').read()
crc = 0
for line in data.splitlines():
    if line.startswith('\ufeff'):
        line = line[1:]
    crc = zlib.crc32(line.encode('utf-8'), crc)
signed = crc - (1 << 32) if crc >= (1 << 31) else crc
print(signed)  # -219537893
```

## 五、后续注意

- **以后若其它 migration 报同样 mismatch**，用同样思路：
  1. `SELECT version, checksum FROM flyway_schema_history WHERE version='XX';` 看库里的值
  2. 用上面 Python 算法对本地对应 `.sql` 重算，得到 Flyway 10.10 权威值
  3. `UPDATE flyway_schema_history SET checksum = <重算值> WHERE version='XX';`（先备份表）
- **`flyway repair-on-migrate=true` 无法解决此问题**：Spring Boot 的 `FlywayMigrationInitializer` 先 `validate` 再 `migrate`，validate 阶段就抛异常，repair 根本没机会执行。需单独更新 checksum。
- **数据库备份表**：`flyway_schema_history_bak_20261010` 保留（repair 前快照），确认稳定后可清理。

## 六、本次涉及的部署操作（server 服务）

1. 本地改 `SecurityConfig.java`（放行 `/route/getConstantRoutes`）→ 去 BOM → `mvn -pl server -am -DskipTests package`
2. 上传新 jar 到 `/opt/wuling/build/server/target/`
3. `docker build` 新镜像 `wuling/server:20261010-cors`
4. 修复 Flyway checksum（见第三节）
5. `IMAGE_TAG=20261010-cors docker compose up -d server`
6. 验证：`curl http://127.0.0.1:8089/route/getConstantRoutes` 返回 `{"code":0,"data":[]}`，不再 401

---

# 运维备忘：后台登录页加载慢（前端静态资源缺 gzip）

> 日期：2026-10-10
> 触发场景：后台登录页（`http://43.136.91.239:8088`）进入缓慢，排查发现并非后端慢。

## 一、问题现象与根因

| 项目         | 说明                                                                      |
| ------------ | ------------------------------------------------------------------------- |
| 现象         | 登录页首屏加载缓慢                                                        |
| 后端接口实测 | `getConstantRoutes` / `quick-login/enabled` / `login` 均约 10ms，后端不慢 |
| 根因         | nginx 8088（前端托管）server 块**缺少 gzip**，大 JS chunk 从公网裸传      |

## 二、关键排查过程

1. 先测接口耗时，确认后端正常（10ms 级）。
2. 看前端产物体积：`router` 1.1MB、`home` 667KB、`AdminListPage` 546KB、`index` 511KB。
3. `nginx -T` 查 gzip 分布：gzip 只在 8089 接口块和 `wulingshiguang.top` 域名块，**8088 前端块没有**。
4. 实测验证：
   ```bash
   curl -s -H "Accept-Encoding: gzip" -o /dev/null -w "%{size_download}\n" \
     http://127.0.0.1:8088/assets/index-5W8AW3T6.js
   # 加 gzip 前：511203 字节（未压缩）
   ```

## 三、本次改动

**文件**：`/etc/nginx/conf.d/wuling-preview.conf`（8088 server 块内新增 gzip）

```nginx
gzip on;
gzip_min_length 1k;
gzip_comp_level 6;
gzip_types text/plain text/css application/json application/javascript text/xml application/xml image/svg+xml;
gzip_vary on;
```

> 参数与 8089 接口块保持一致。

操作：`nginx -t` 校验通过 → `nginx -s reload`（已备份 `wuling-preview.conf.bak-20261010`）。

## 四、效果（实测）

| 资源      | 压缩前 | 压缩后 | 压缩率 |
| --------- | ------ | ------ | ------ |
| index.js  | 511KB  | 142KB  | 72%    |
| router.js | 1.1MB  | 336KB  | 70%    |
| home.js   | 667KB  | 221KB  | 67%    |

总传输体积约 2.3MB → 700KB。

## 五、后续注意

- 该 nginx 文件是「备案审核期间的临时预览配置」（文件头注释已说明，备案通过后由 `wuling.conf` 接管），**若切换正式域名配置，需在正式 server 块同样确认 gzip 已开启**。
- 前端 `assets/` 已设 `expires 7d` 缓存，改动后用户端需强刷（Ctrl+Shift+R）或清缓存，否则旧浏览器会继续用未压缩的缓存资源。
