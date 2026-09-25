# 全站字段写库排查与补齐方案

> 日期：2026-09-25
> 触发：用户在「平台主体」页看到告警「字段 appid、withdrawFreeAuditThreshold 未写入数据库」
> 目标：**把所有新增/修改的字段都真正落到数据库**，彻底消除「页面无反应」问题

---

## 一、排查方法（可复现）

写了一个三方比对脚本，自动核对：

1. **前端**：扫描 58 个页面，提取每个页面 `formFields` 的字段
2. **后端**：解析 `CrudRegistry` 的白名单（可写入列）
3. **数据库**：导出全部 59 张表的真实列

判定规则：表单字段若不在白名单中，提交时会被 `CrudService.filterWritable()` **静默丢弃**。

---

## 二、排查结果总览

**12 个页面存在字段丢失问题**，但性质分三类，处理方式完全不同：

| 类型 | 说明 | 页面数 | 是否需要改库 |
|------|------|--------|-------------|
| **A. 真丢失** | 走通用 CRUD，字段既不在白名单也无对应列/走错表 | 9 | 需要 |
| **B. 表用错** | 数据其实存到了别的表，但通用 CRUD 查不到 | 3 | 需要（改查询） |
| **C. 误报** | 走了专用接口，实际已正确落库 | 2 | 不需要 |

---

## 三、逐页明细

### A 类：真丢失（需补列 + 加白名单）

#### A1. 平台主体 `subject/platform` —— 用户截图中的问题

| 字段 | 现状 | 应该存到哪 |
|------|------|-----------|
| `appid` | 通用 CRUD 丢弃（但 `savePlatformProfile` 已正确写入 `platform_profile.app_id`）| 已 OK，仅告警误导 |
| `appSecret` | 同上（写入 `platform_profile.app_secret`）| 已 OK |
| `mchId` | 同上（写入 `platform_profile.pay_config` JSON）| 已 OK |
| **`withdrawFreeAuditThreshold`** | **两处都没存 → 真丢失** | 需新增列 |

> **根因**：该页同时走两条写入路径 —— 「通用 CRUD 写 `biz_subject`」+「专用接口写 `platform_profile`」。
> `appid/appSecret/mchId` 靠专用接口存住了，但 `withdrawFreeAuditThreshold` 只走了通用 CRUD，而
> `biz_subject` 根本没这列 → 彻底丢失。**告警本身是对的。**

#### A2. 城市管理 `system/city`（资源 `cities`，表 `region`）

`region` 表只有 `id,parent_id,code,name,level,sort`，而页面要写：

| 字段 | 处理 |
|------|------|
| `latitude` | 需新增列 |
| `longitude` | 需新增列 |
| `provinceCode` | 需新增列（或改用 `parent_id` 关联）|

#### A3. 数据字典 `system/dict`（资源 `dictEntries`，表 `sys_dict_item`）

页面用 `code/name/groupName`，但表里是 `item_code/item_name/dict_type`：

| 页面字段 | 表列 | 处理 |
|----------|------|------|
| `code` | `item_code` | 前端改名，或后端做字段别名 |
| `name` | `item_name` | 同上 |
| `groupName` | `dict_type` | 同上 |

> 因为三个字段全不匹配，实测直接报「**没有可写入的字段**」——新增字典完全不可用。

#### A4. 优惠券 `marketing/coupon`（资源 `coupons`，表 `coupon`）

| 页面字段 | 表列 | 处理 |
|----------|------|------|
| `title` | `name` | 前端改名 |
| `condition` | `threshold` | 前端改名 |
| `quantity` | `stock` | 前端改名 |
| `expiryText` | 无 | 需后端生成或新增列 |
| `validityPeriod` | `validity_start`/`validity_end` | 前端拆分 |
| `channel` | 无 | 需新增列 |
| `paymentRestriction` | 无 | 需新增列 |

> 实测：传入 `title/condition/quantity` 时接口**直接 500 报错**（不是静默丢弃）。

#### A5. 礼品卡 `marketing/gift`（资源 `giftCards`）

| 字段 | 处理 |
|------|------|
| `image` | 表里是 `card_image` → 前端改名 |

#### A6. 会员等级 `marketing/member`（资源 `memberLevels`，表 `member_level`）

| 字段 | 表列 | 处理 |
|------|------|------|
| `level` | `level_code` | 前端改名 |
| `condition` | `amount_target` | 前端改名 |

#### A7. 积分商品 `marketing/points`（资源 `pointsProducts`）

| 字段 | 表列 | 处理 |
|------|------|------|
| `category` | `category` | **库中已有列** → 仅需加白名单 |

#### A8. 授权管理 `auth/grant`（资源 `grants`，表 `user_role_grant`）

| 字段 | 表列 | 处理 |
|------|------|------|
| `role` | `role_code` | 前端改名 |
| `subject` | `subject_id` | 前端改名 |

#### A9. 微信绑定 `auth/wechat`（资源 `users`，表 `app_user`）

| 字段 | 表列 | 处理 |
|------|------|------|
| `openId` | `open_id` | **库中已有列** → 仅需加白名单 |
| `userId` | `id` | 前端改名（该页通常只读）|

### B 类：表用错（数据存到了别的表）

#### B1. 门店管理 `subject/store`

页面写的是 `address/city/manager/phone/storeType`，看起来像丢失，
**但实际走了 `updateAdminStore` 专用接口**，数据存进 `store_profile` 表 —— **没有丢失**。
问题在于「列表查询读 `biz_subject`，明细在 `store_profile`」，容易出现「改完列表不更新」。

#### B2. 渠道管理 `subject/channel`

`location`/`storeType` 存进 `channel_profile` 表（专用接口），**没丢失**。

#### B3. 商品管理 `product/list`

走 `product-service` 专用接口（`/api/v1/admin/product/**`），**没走通用 CRUD**，
我的脚本误报了 `productCategories`。实际字段落在 `product` 表，需单独核对。

### C 类：误报（无需处理）

- `subject/store` 的 `store.update('subjects', ...)` 实际只用于**删除**，不是表单提交路径
- `subject/channel` 同上

---

## 四、调整方案

### 原则

**优先改前端字段名去适配已有列**，而不是见字段就加列 —— 因为：
- 同一语义已有列（如 `name` vs `title`）再加一列会造成**双份数据、语义分裂**；
- 只有「库里确实没有对应语义」时才新增列。

### 阶段 1：纯前端改名（无需改库，共 6 个页面）

| 页面 | 改动 |
|------|------|
| 数据字典 | `code→itemCode`、`name→itemName`、`groupName→dictType` |
| 优惠券 | `title→name`、`condition→threshold`、`quantity→stock` |
| 会员等级 | `level→levelCode`、`condition→amountTarget` |
| 礼品卡 | `image→cardImage` |
| 授权管理 | `role→roleCode`、`subject→subjectId` |
| 微信绑定 | `openId` 保持（加白名单）、`userId` 移除表单项 |

### 阶段 2：后端加白名单（列已存在，共 2 处）

- `pointsProducts`：白名单补 `category`
- `users`：白名单补 `open_id`

### 阶段 3：新增数据库列（语义确实缺失）

```sql
-- 平台主体：免审阈值
ALTER TABLE biz_subject ADD COLUMN withdraw_free_audit_threshold BIGINT NOT NULL DEFAULT 0
  COMMENT '提现免审阈值（分），0=无免审';

-- 城市：经纬度与省份
ALTER TABLE region
  ADD COLUMN latitude  DECIMAL(10,6) NULL COMMENT '纬度',
  ADD COLUMN longitude DECIMAL(10,6) NULL COMMENT '经度',
  ADD COLUMN province_code VARCHAR(64) NULL COMMENT '所属省份编码';

-- 优惠券：渠道与支付限制
ALTER TABLE coupon
  ADD COLUMN channel VARCHAR(64) NULL COMMENT '发放渠道',
  ADD COLUMN payment_restriction VARCHAR(64) NULL COMMENT '支付方式限制';
```

### 阶段 4：同步 CrudRegistry 白名单

将上述新增列登记进对应资源白名单。

### 阶段 5：消除「双路径写入」隐患

`platform` 页同时用「通用 CRUD + 专用接口」写同一份数据，是告警的来源。
建议：**平台配置全部走 `savePlatformProfile` 专用接口**，移除 `store.add/update('subjects')` 的字段传递。

### 阶段 6：告警文案优化

当前告警说「请联系开发检查后端白名单配置」，但对上述「改前端字段名」的页面，
更准确的提示应是「字段名与数据库不匹配」。建议拆分成两种提示。

---

## 五、验收标准

用同一套排查脚本复跑，要求：**0 个页面存在字段丢失**。

并且逐页手测「新增 → 保存 → 刷新 → 数据还在」。

---

## 六、执行记录与验证结果（2026-09-25）

### 用户确认的两项决策

1. **优惠券有效期**：改为**日期区间**（`validity_start` ~ `validity_end`）
2. **城市-省份关系**：**沿用 `region.parent_id` 关联**，不新增 `province_code` 列

### 阶段 1：前端字段改名（已完成，6 个页面）

| 页面 | 改动 | 说明 |
|------|------|------|
| 数据字典 | `code→itemCode`、`name→itemName`、`groupName→dictType` | 原三字段全不符，新增直接报错 |
| 会员等级 | `level→levelCode`，移除库中不存在的 `condition` | 另修正 `benefits` 为结构化的 icon/text/count 编辑 |
| 优惠券 | `title→name`、`condition→threshold`、`quantity→stock`；有效期改日期区间 | 原提交直接 500 |
| 礼品卡 | `image→cardImage` | 库中列为 `card_image` |
| 授权管理 | `role→roleCode`、`subject→subjectId`；角色取值改库中原值（STORE/CHANNEL/...） | 原用中文值，与库不符 |
| 微信绑定 | `openId` 走 `open_id`；移除不能手填的 `userId` | 补充展示记录 id |

### 阶段 2：白名单补充（列已存在）

- `users` 补 `open_id`（同时加入 searchable）
- `pointsProducts` 补 `category`
- `giftCards` 补 `card_image`
- `coupons` 补 `validity_start`、`validity_end`、`channel`、`payment_restriction`，并补 `status` 为 filterable

### 阶段 3：数据库新增列（`V31__field_write_gap_fix.sql`）

| 表 | 新增列 | 用途 |
|----|--------|------|
| `biz_subject` | `withdraw_free_audit_threshold` | 平台提现免审阈值（分） |
| `coupon` | `channel`、`payment_restriction` | 优惠券渠道与支付限制 |
| `region` | `latitude`、`longitude` | 城市经纬度（回填长沙/广州/深圳） |

**执行方式**：直接改库（本地=生产同一实例），并在同一脚本内**同步登记 Flyway 历史**，
避免重演 V30「人工改库后 Flyway 报失败」的问题。

备份：`/opt/wuling/backup/v31-pre-20260925-093119.sql` 与对应结构快照。

### 阶段 5：消除双路径写入（平台主体页）

把表单字段按归属表拆成两组分别写入：

| 分组 | 字段 | 写入路径 |
|------|------|---------|
| A. `biz_subject` | code / name / status / **withdrawFreeAuditThreshold** | 通用 CRUD（subjects）|
| B. `platform_profile` | appid / appSecret / mchId | 专用接口 savePlatformProfile |

原先两组字段被一并交给通用 CRUD，导致 A 组的 `withdrawFreeAuditThreshold` 被静默丢弃
（B 组靠专用接口侥幸存住）—— 这正是用户截图中告警的根因。

### 验证结果

| 验证项 | 结果 |
|--------|------|
| 排查脚本复跑（含显式 payload 的 14 个页面） | **0 处字段丢失** ✅ |
| 排查脚本复跑（`...data` 整体展开的 3 处） | **0 处字段丢失** ✅ |
| 后端 `mvn test` | 40 tests, 0 failures ✅ |
| 前端 `vue-tsc` | exit=0 ✅ |
| `oxlint` 目标文件 | 0 错误 ✅ |

**接口实测（对本地新代码）**：

| 页面 | 原先问题 | 实测结果 |
|------|---------|---------|
| 数据字典 | 「没有可写入的字段」 | `itemCode/itemName/dictType` 全部落库 ✅ |
| 平台主体 | `withdrawFreeAuditThreshold` 丢弃 | 50000 分（500 元）成功落库 ✅ |
| 城市管理 | 经纬度丢弃 | `parentId=1` + 经纬度全部落库 ✅ |
| 优惠券 | 提交直接 500 | 含日期区间与 `channel` 全部落库 ✅ |
| 会员等级 | `level` 丢弃 | `levelCode/amountTarget` 落库 ✅ |
| 积分商品/礼品卡/用户 | 字段缺失 | `category`/`cardImage`/`openId` 均已返回 ✅ |

### 说明：三个「非问题」页面（脚本误报）

经逐个核实，以下页面**并未丢字段**，只是走的是专用接口而非通用 CRUD：

| 页面 | 实际写入路径 |
|------|-------------|
| 门店管理 | `updateAdminStore` / `addAdminStore` → `store_profile` 表 |
| 渠道管理 | `createSubjectChannel` / `updateSubjectChannel` → `channel_profile` 表 |
| 商品管理 | `addProduct` / `editProduct` → product-service 专用接口 |

> 已改进排查脚本，仅统计**真正调用 `store.add/update/patch`** 的写入路径，消除误报。

### 后续待办

- [ ] 部署后端（server 镜像）与前端，使修复在线上生效
- [ ] 部署后逐页手测「新增 → 保存 → 刷新 → 数据还在」
- [ ] 建议补充页面级自动化用例，防止字段名再次漂移

---

## 七、生产部署记录（2026-09-25）

| 项 | 内容 |
|----|------|
| 后端镜像 | `wuling/server:20260925-095629`（tag 为 `local`） |
| 前端 | `prod-ip` 模式，部署至 `/opt/wuling/web` |
| 数据库 | Flyway 自行执行 V31（checksum `-201096411`，success=1） |

### 回滚点

| 项 | 位置 |
|----|------|
| 旧后端镜像 | `wuling/server:pre-v31-20260925-094714` |
| 旧前端目录 | `/opt/wuling/web.prev-20260925-095848` |
| 旧 jar | `/opt/wuling/build/server/target/server.jar.bak-v31-20260925-094714` |
| 数据库 | `/opt/wuling/backup/v31-pre-20260925-093119.sql` + 结构快照 |

### 部署中遇到的问题与根因（重要教训）

#### 问题：V31 checksum mismatch，服务无法启动

**现象**：新容器启动即退出，日志报：

```
Migration checksum mismatch for migration version 31
-> Applied to database : 0
-> Resolved locally    : -161956936
```

**根因（我的操作失误）**：

我在执行 V31 DDL 时，为了「避免 V30 那种人工改库后 Flyway 报失败」的问题，
手工往 `flyway_schema_history` 插了一条 V31 记录，但**填的 checksum 是 0**——
而 Flyway 对 SQL 文件计算的真实 checksum 并非 0，导致校验不通过。

**这暴露了一个更本质的问题**：手工伪造 Flyway 历史记录是不可靠的做法。
正确的做法是让 **Flyway 自己执行并记录**迁移。

**最终处理（正确姿势）**：

1. 删除我手工插入的 V31 记录
2. **把 V31 脚本改写为幂等**（用 `information_schema` 判断列是否存在，存在则跳过）
3. 重新构建镜像 → Flyway 自己执行 V31 并记录正确 checksum

幂等改写是关键：这样无论列是否已存在，脚本都能安全执行，
Flyway 也就无需任何人工干预。**今后新增迁移一律采用此写法。**

### 生产验证结果

| 验证项 | 结果 |
|--------|------|
| 容器健康 | `wuling-server` healthy ✅ |
| 冒烟测试 | 通过 7 项，失败 0 项 ✅ |
| Flyway | V31 由 Flyway 自行执行，success=1 ✅ |
| 数据字典 itemCode/itemName/dictType | 落库成功 ✅ |
| 平台主体 withdrawFreeAuditThreshold | 落库成功 ✅ |
| 城市 parentId + 经纬度 | 落库成功 ✅ |
| 优惠券日期区间 + channel | 落库成功 ✅ |
| 会员等级 levelCode/amountTarget | 落库成功 ✅ |
| 积分商品 category / 礼品卡 cardImage / 用户 openId | 字段均已可用 ✅ |
| 审计留痕 | 5 次写操作全部留痕，operator=super ✅ |
| 全部 12 个容器 | 运行正常 ✅ |

### 待办

- [ ] 前端备份目录已积累 5 个（`web.bak-*` / `web.prev-*`），后续可清理
- [ ] 建议为「字段与库列一致性」补充自动化检查，纳入 CI，防止字段名再次漂移

---

## 八、CI 检查与运维清理（2026-09-25）

### 1. 字段写库一致性 CI 检查 ✅

把「字段名漂移」从**人工排查**变成 **CI 自动拦截**。

**新增文件**：

| 文件 | 作用 |
|------|------|
| `server/src/test/java/com/wuling/common/FieldWriteConsistencyTest.java` | 检查逻辑 |
| `.github/workflows/field-write-consistency.yml` | CI 工作流（PR + push 触发）|

**两个检查项**：

| 测试 | 校验内容 |
|------|---------|
| `formFieldsMustMatchRegistryWhitelist` | 前端表单字段是否都在 `CrudRegistry` 白名单内 |
| `registryColumnsMustExistInMigrations` | 白名单中的列是否都能在迁移脚本里找到 |

**为什么做成 JUnit 测试而非独立脚本**：项目已有 `MigrationFilesTest` 先例，
复用既有 Maven 测试链路即可，无需额外引入 Node/脚本运行环境。

**关键设计（踩过的坑）**：

1. **只校验 onSubmit 内真正走通用 CRUD 的写入** —— rowActions 里的
   「启用/停用」也会调 `store.patch('subjects', ...)`，但那写的是固定 `{status}`，
   若扫描整个文件会把走专用接口的页面误判。
2. **优先按实际 payload 判定**，而非表单全字段 —— 平台主体页的表单含两组字段，
   提交时按归属拆分写入，用表单全字段判定会误报。
3. **显式 payload 内含 `...data` 时并入 formFields** —— 否则会漏掉通过展开传入的字段
   （这个漏检点是在「验证测试本身」时发现的）。
4. **解析 ALTER TABLE 需支持多列写法** —— `ADD COLUMN a ..., ADD COLUMN b ...`
   按整条语句抓取再逐列提取。

**已验证测试确实能抓出问题**：临时把 `dict` 页的 `itemCode` 改回 `code`，
测试如期失败并给出精确提示：

```
页面 src/views/system/dict/index.vue 的资源 dictEntries（表 sys_dict_item）中，
字段 [code] 不在 CrudRegistry 白名单内，提交后会被静默丢弃。
请二选一：① 前端改用库中真实列名；② 若确需新列，先在 V*.sql 中建列再登记白名单。
```

还原后测试恢复通过。（先写测试再「测试测试」，避免出现永远通过的空壳检查。）

### 2. 服务器前端备份清理 ✅

删除 3 个更早的备份目录，保留最新回滚点：

| 目录 | 处理 |
|------|------|
| `/opt/wuling/web` | 保留（运行中）|
| `/opt/wuling/web.prev-20260925-095848` | 保留（最新回滚点）|
| `web.bak-20260924-235528` | 删除 |
| `web.bak-20260925-094714` | 删除 |
| `web.prev-20260925-000636` | 删除 |

删除前校验：目录含 `index.html`（确为前端产物）、路径在白名单内。
清理后首页仍 HTTP 200。

### 当前测试总览

| 测试类 | 用例数 |
|--------|--------|
| JwtSecretValidationTest | 6 |
| JwtTokenProviderTest | 3 |
| GeoCodeSignTest | 5 |
| **FieldWriteConsistencyTest（新增）** | **2** |
| MigrationFilesTest | 1 |
| ResultTest | 3 |
| InvestorThresholdTest | 13 |
| SplitCalculatorTest | 9 |
| **合计** | **42（原 40 + 新增 2）** |
