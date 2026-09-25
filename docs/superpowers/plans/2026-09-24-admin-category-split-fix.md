# 后台三问题修复方案（分类管理 / 分账规则 / 写库可验证性）

> 日期：2026-09-24
> 来源：用户反馈图一「分类管理」、图二「分账规则」+ 「为什么我改数据没有同步更新」
> 关系：本文档承接 `2026-09-23-admin-full-db-write.md`（阶段 2 已把 store 写操作改为「先接口后本地」）。
> 阶段 2 解决了「失败不静默」，但**没有解决「字段被后端静默丢弃」**——即本轮的根因。

---

## 一、根因诊断（已直连 MySQL 核验）

核验环境：`127.0.0.1:13306/wuling`，用户 `wuling`。

### 统一病灶

后台走「通用 CRUD」：`前端 → crudCreate/Update → CrudController → CrudService`。
`CrudService.filterWritable()` 会把**不在 `CrudRegistry` 白名单里的字段静默 `continue` 丢弃**，
接口仍返回 200。于是「前端字段名/表字段名不一致」不会报错，只表现为**页面读不到值**。

### 问题一：分类管理

真实表 `product_category`：`id, parent_id, code, name, type, sort, create_time, update_time, deleted`。
**没有 `enabled`，没有 `tag`，`type` 为 NOT NULL 且无默认值。**

| 现象 | 根因 |
|------|------|
| 状态列恒为「—」 | 前端读 `row.enabled`，表无此列 → `undefined` → renderTag 兜底「—」 |
| 分类标签列恒为「—」 | 前端读 `row.tag`，表无此列 |
| 新增/修改后页面不显示 | 白名单 `parent_id, code, name, type, sort` 不含 `tag`/`enabled` → 静默丢弃；且表单无 `type` 字段，而 DB `type` NOT NULL → 新增必然失败 |
| 按状态查询无效 | `enabled` 不在 `searchable`/`filterable` → 条件被丢弃 → 返回全部 |
| 排序全为 0 | 8 条数据 `sort` 均为 0，`order by sort asc` 下顺序不稳定 |

### 问题二：分账规则

真实表 `split_rule` 为**万分比五方**：`platform_ratio=1000, store_ratio=5000, channel_ratio=1500, investor_ratio=1500, supplier_ratio=1000`（合计 10000）。

| 现象 | 根因 |
|------|------|
| 作用范围显示 `GLOBAL`/`PRODUCT` | 前端列直接输出原值，无中文映射（`COMMON_STATUS_LABELS` 也未收录） |
| 门店金额/资源方金额/投资人比例空 | 前端读 `storePerItem`/`channelPerItem`/`investorPercent`，后端返回 `storeRatio`/`channelRatio`/`investorRatio` → 全 `undefined` |
| 「改了没反应」 | 反证：`SR-1001.update_time=21:18:32` 晚于 `create_time=21:16:48`，写其实已落库，仅前端字段名对不上 |

### 问题三：按钮是否都写库

| 层次 | 问题 |
|------|------|
| 后端无审计 | `audit_log` 33 行全为 `AUTH_LOGIN_FAIL`/`WITHDRAW_ADMIN_APPLY`/`PAY_CALLBACK_REJECT`，**0 条 CRUD 记录**；`CrudController` 不写审计 |
| 静默丢弃 | 见上文统一病灶，白名单外字段被丢弃但仍返回成功 |
| 纯本地动作 | `toggleFeature()`、`enableSplitRule()` 只改 localStorage + 本地 `audit()`，**不调后端**。同页 `SR-1000 停用`走 `patch`（真写库）、`SR-1001 启用`走 `enableSplitRule`（假写）→ 观感「有的同步有的没同步」 |

---

## 二、已确认的业务决策

1. **分类状态字段**：加字段 + 修白名单（新增 `enabled` 与 `tag` 两列）
2. **分账金额口径**：改成比例显示（`门店比例/资源方比例/投资人比例`，由库中万分比换算）
3. **投资人阈值口径**：按投资人当月累计分账额，达标后用「达标比例」
4. **缺失字段一律在数据库补齐**（本轮新增，见步骤 1）

---

## 三、字段补齐清单（本轮要新增的数据库列）

> 原则：**凡是页面要用、而表里没有的字段，一律在数据库补列，不用前端造假数据兜底。**

### `product_category`（分类管理）

| 新增列 | 类型 | 默认 | 用途 | 对应界面 |
|--------|------|------|------|---------|
| `tag` | `VARCHAR(64) NULL` | NULL | 分类标签 | 图一「分类标签」列 + 新增/编辑表单 |
| `enabled` | `TINYINT NOT NULL` | `1` | 状态：1 启用 / 0 停用 | 图一「状态」列 + 状态查询 + 停用/启用按钮 |

### `split_rule`（分账规则）

| 新增列 | 类型 | 默认 | 用途 | 对应界面 |
|--------|------|------|------|---------|
| `investor_threshold_amount` | `BIGINT NOT NULL` | `0` | 投资人当月累计分账达标额（分），0 = 不启用阈值规则 | 图二新增表单项 |
| `investor_ratio_after` | `INT NOT NULL` | `0` | 达标后的投资人比例（万分比） | 图二新增列 + 表单项 |

> 说明：图二的「门店/件(元)」「资源方/件(元)」「投资人比例」三列**不需要加字段**——
> 库中 `store_ratio`/`channel_ratio`/`investor_ratio` 已存在，问题是前端字段名写错了，
> 按决策 2 改为比例展示即可。

---

## 四、执行计划

### 阶段 1：补齐数据库字段 ✅ 已完成（2026-09-24）

> 状态：**已执行**。本地库与生产库为同一实例（`127.0.0.1:13306/wuling`），
> 故生产环境无需重复调整。迁移前已导出回滚备份 `.tmp_backup_before_v30.json`。
>
> 执行结果：`product_category` 新增 `tag`/`enabled` 且 sort 回填为 1~8（8 行）；
> `split_rule` 新增两列且 `investor_ratio_after` 回填为原 `investor_ratio`（5 行）；
> 三条规则五方合计仍均为 10000，分账行为未改变。

#### 步骤 1.1 编写迁移脚本 `V30__category_and_split_rule_enhance.sql`

位置：`server/src/main/resources/db/migration/V30__category_and_split_rule_enhance.sql`

```sql
-- =============================================================
-- 分类管理 & 分账规则：补齐后台页面所需字段
-- 背景：后台「分类管理」页要维护状态与分类标签，但 product_category
--       原本只有 code/name/type/sort，导致字段被 CRUD 白名单静默丢弃；
--       「分账规则」页需要配置「投资人当月达标后比例」，split_rule 亦无对应列。
-- =============================================================

-- ---------- 1. 分类管理：补 tag / enabled ----------
ALTER TABLE product_category
    ADD COLUMN tag VARCHAR(64) NULL COMMENT '分类标签' AFTER name,
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用' AFTER sort;

-- 历史数据：8 条 sort 全为 0，order by sort asc 下顺序不稳定，按 id 回填初始顺序
UPDATE product_category SET sort = id WHERE sort = 0;

-- ---------- 2. 分账规则：补投资人阈值配置 ----------
ALTER TABLE split_rule
    ADD COLUMN investor_threshold_amount BIGINT NOT NULL DEFAULT 0
        COMMENT '投资人当月累计分账达标额（分），0=不启用阈值规则' AFTER investor_ratio,
    ADD COLUMN investor_ratio_after INT NOT NULL DEFAULT 0
        COMMENT '达标后投资人比例（万分比）' AFTER investor_threshold_amount;

-- 历史数据回填：默认「不启用阈值」，达标比例沿用原比例，保证行为不变
UPDATE split_rule SET investor_ratio_after = investor_ratio
    WHERE investor_threshold_amount = 0;
```

#### 步骤 1.2 执行迁移 ✅ 已执行

- 采用**方式 2**：直接改库。因本地库与生产库为同一实例，改完生产无需再调整
- 执行前先 `SHOW COLUMNS` 确认 4 个目标列均不存在（无一已存在，无重复列风险）
- 执行前导出 `product_category` / `split_rule` 的 DDL 与全量数据作为回滚备份
- 在单个事务中执行全部 DDL，失败即整体回滚
- `V30` 脚本已按编号续接（上一个为 `V29`），供 Flyway 环境与版本追溯使用

#### 步骤 1.3 迁移后校验 ✅ 已通过

```sql
SHOW COLUMNS FROM product_category LIKE 'tag';
SHOW COLUMNS FROM product_category LIKE 'enabled';
SHOW COLUMNS FROM split_rule LIKE 'investor_threshold_amount';
SHOW COLUMNS FROM split_rule LIKE 'investor_ratio_after';
SELECT id, code, name, tag, sort, enabled FROM product_category WHERE deleted = 0 ORDER BY sort;
```

实测结果（均已通过）：

| 校验项 | 结果 |
|--------|------|
| `product_category.tag` | `varchar(64)` NULL `分类标签` ✅ |
| `product_category.enabled` | `tinyint` NOT NULL default 1 `状态 1启用 0停用` ✅ |
| `product_category` 8 条 | `enabled` 全为 1；`sort` 由全 0 变为 1~8 ✅ |
| `split_rule.investor_threshold_amount` | `bigint` NOT NULL default 0 ✅ |
| `split_rule.investor_ratio_after` | `int` NOT NULL default 0 ✅ |
| `split_rule` 回填 | `investor_ratio_after` = 原 `investor_ratio`（1500/1500/1000）✅ |
| 五方合计 | SR-1000/1001/1002 均仍为 10000 ✅ |

回滚备份：`.tmp_backup_before_v30.json`（含两表迁移前的 `SHOW CREATE TABLE` 与全量数据）

---

## 四之二、后续阶段进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| 1 | 补齐数据库字段（V30 迁移） | ✅ 已完成 |
| 2 | 后端 `CrudRegistry` 白名单放行新字段 | ✅ 已完成 |
| 3 | 前端分类页 / 分账规则页对齐新字段 | ✅ 已完成 |
| 4 | 消灭「假写库」+ 补审计 | ✅ 已完成 |
| 5 | 分账引擎接入投资人阈值 | ✅ 已完成（TDD，13 项新单测） |

### 阶段 5 完成记录（2026-09-24）

采用 **TDD**：先写 13 项单测（RED，编译失败）→ 再实现（GREEN）→ 全绿。

#### 数据层

`SettlementRecordMapper.selectSumCurrentMonth(subjectId, monthStart, monthEnd)`

- 统计投资人**当月自然月**内累计已分账金额（分）
- **不限定结算状态**：只要已分账即计入「累计」，达标衡量的是业务贡献而非到账进度
- **用 `create_time` 而非 `settle_date`**：分账记录生成当下即代表当月业绩完成；
  `settle_date` 是 T+1 结算日期，用它会导致月末订单被算进下月
- 跨月自动归零，符合「当月目标」语义

#### 领域模型 `SplitRule`

新增两个字段（V30 已加列）：`investorThresholdAmount`、`investorRatioAfter`，以及：

| 方法 | 职责 |
|------|------|
| `resolveInvestorRatio(累计额)` | 解析本单生效的投资人比例：未启用/未配置 → 原比例；达标 → 达标后比例 |
| `resolvePlatformRatio(累计额)` | 平台 = 原平台 − (达标比例 − 原投资人比例)，保证五方合计恒为 10000 |

边界口径：**累计额恰好等于阈值即视为达标**（阈值语义是「达到」）。

#### 计算引擎 `SplitCalculator`

- 新增 7 参重载 `calc(..., accumulatedInvestorAmount)`；原 6 参重载保留并委托（传 null）
- 校验改为针对**生效后**的比例合计（而非规则上的原比例）
- **新增「平台比例为负」校验**：若达标后比例增量超过原平台比例，平台会倒贴钱，
  此时即便合计仍为 10000 也必须抛错，否则会算出负的平台收入并写进快照与台账

#### 结算服务 `LedgerService`

- `executeSplit` 在调用 `calc` 前，先取投资人主体与当月累计额并传入
- `investorSubjectId` 只查询一次并复用于台账入账，避免两次查询结果不一致
- 未绑定投资人时累计额按 0 处理（不启用阈值判定）

#### 前端 `src/views/product/split/index.vue`

新增 `validateInvestorThreshold()`：达标增量超过原平台比例时提前拦截，
给出明确中文提示（后端也会抛错，前端拦截是为了更友好的反馈）。

#### 测试覆盖（13 项）

| 场景 | 用例 |
|------|------|
| 未达标 | 累计 99999 用原比例；金额与基准完全一致 |
| 达标当单 | 累计恰好 = 阈值即启用新比例 |
| 达标后 | 投资人增量 == 平台减量；五方之和仍等于实付 |
| 阈值为 0 | 累计再大也不触发，与原行为一致 |
| 达标比例为 0 | 视为未配置，退回原比例 |
| 边界 | 累计额为 null 按 0；跨阈值四档取值正确 |
| 非法配置 | 平台比例被让成负数时抛错 |

#### 验证

| 验证项 | 结果 |
|--------|------|
| `mvn -pl server test` | **40 tests, 0 failures**（原 27 + 新增 13）✅ |
| 实体字段 ↔ 库列映射 | 16 个字段全部对上，无缺失列 ✅ |
| 当月累计查询（真实库） | subject 201=5765 分、202=477 分、203=0 分，SQL 可执行 ✅ |
| 前端 `vue-tsc` | exit=0 ✅ |

> **上线注意**：阈值默认 0（不启用），达标比例默认等于原比例，
> 因此本次上线**不改变任何既有订单的分账结果**，需人工配置后才生效。

### 阶段 4 完成记录（2026-09-24）

#### 后端：CRUD 写操作落审计（治本）

文件：`server/src/main/java/com/wuling/system/crud/CrudService.java`

- `create` / `update` / `delete` 分别写入 `audit_log`（动作：新增/编辑/删除）
- 记录 `operator`（Spring Security 上下文，取不到记 `system`）、`module`（资源名）、
  `target`（优先 name/code）、`before_value` / `after_value`（变更前后快照）、
  `reason`、`ip`；与业务写操作**同事务**，失败一并回滚
- 审计写入本身做了 try/catch 兜底：审计失败只记 WARN，不阻断业务
  （避免因审计表结构差异导致业务写不进去）
- 字段按 `audit_log` 列长度截断，防止超长插入失败

**根因修复**：此前 `audit_log` 33 行全是登录/提现等记录，CRUD 操作 0 条 ——
因为前端只在 localStorage 记「假审计」，后端 `CrudController` 完全不写审计。
改为后端落库后，审计不再依赖调用方自觉。

#### 后端：白名单静默丢弃改为可排查

- `filterWritable()` 对**被丢弃的非白名单字段**记 WARN 日志（含资源名与字段名列表）
- 不抛错（多传字段不算错误），但问题可直接从服务端日志定位

#### 前端：写库后比对，杜绝假成功

文件：`src/store/modules/admin/index.ts`

- 新增 `warnDroppedFields()`：写库后把「提交值」与「后端回读值」逐一比对，
  发现明确规定过却未落库的字段时弹出告警（如 `分类管理：字段 tag、enabled 未写入数据库`）
- 已在 `add` / `update` / `patch` 的远端分支接入
- `patch` 远端分支改为**以后端返回值为准**回填本地镜像，避免本地自造字段与库中不一致

#### 前端：清理无调用方的本地版实现

以下 5 个函数已无任何页面调用，属「留着重容易被误用」的死代码，一并删除：

| 函数 | 问题 |
|------|------|
| `enableSplitRule` | 本地做「同范围唯一启用」，与后端规则不一致（页面已改走 patch） |
| `bindInvestorToStore` | 写 `subjects` 的 `investorId`/`investorRelatedStoreIds`，**这些列在 biz_subject 中不存在**，必然被白名单丢弃 |
| `unbindInvestorFromStore` | 同上 |
| `bindResourceToStore` | 写 `boundStoreIds`/`boundStoreCount`，同样不存在于 biz_subject |
| `unbindResourceFromStore` | 同上 |

> 正确链路是 `bindStoreInvestor` / `bindChannelStore`（走 dedicated 接口，
> 落 `store_profile.investor_subject_id` 与 `channel_store` 表），页面已在用。
> 同时移除了 `toggleSplitRule` 的 store 导入（已无调用方）。

#### 验证

| 验证项 | 结果 |
|--------|------|
| 后端 `mvn -pl server -am compile` | exit=0 ✅ |
| 后端 `mvn -pl server test` | 27 tests, 0 failures ✅ |
| 前端 `vue-tsc --noEmit` | exit=0 ✅ |
| `oxlint` 目标文件 | 0 错误（store 文件由 11 降为 10 个历史告警）✅ |
| 审计写入链路 | 模拟写入并回读成功，事务回滚后库中无残留 ✅ |
| 失效函数残留 | 全库检索确认无调用方残留 ✅ |

### 阶段 2 完成记录（2026-09-24）

文件：`server/src/main/java/com/wuling/system/crud/CrudRegistry.java`

- `productCategories`：`writable` 补 `tag`、`enabled`；`filterable` 补 `enabled`
- `splitRules`：`writable` 补 `investor_threshold_amount`、`investor_ratio_after`；
  `filterable` 补 `scope`（供「范围」下拉精确筛选）
- 验证：`mvn -pl server -am compile` 通过

### 阶段 3 完成记录（2026-09-24）

**分类管理** `src/views/product/category/index.vue`

- 状态列改为读 `enabled`（数字 1/0 -> 启用/停用）
- 搜索「状态」改用 `eq_enabled` 等值过滤（原先按 LIKE，条件被丢弃）
- 表单补 `type`（必填，DB 为 NOT NULL）与 `enabled`、`tag`
- `toFormData` / `onSubmit` 做类型归一化，避免 NSelect 值类型不匹配导致「刚存就查不到」

**分账规则** `src/views/product/split/index.vue`

- 列口径修正：`门店比例/资源方比例/投资人比例`（读 `storeRatio`/`channelRatio`/`investorRatio`，
  由万分比换算百分比），修复原 `storePerItem` 等错误字段名导致的三列恒空
- 「作用范围」中文映射 `GLOBAL -> 全局`、`PRODUCT -> 商品`
- 搜索「范围」value 改为数据库原值并走 `eq_scope` 等值过滤（原为中文，恒查不到）
- 新增列/表单项：`投资人达标额`、`达标后比例`
- 提交前校验五方合计 = 10000（与 `SplitCalculator` 一致，前端提前拦截）
- **启用按钮改走 `store.patch` 真写库**，修复原 `enableSplitRule()` 只改本地镜像不落库的问题
- 验证：`vue-tsc` 0 错误、`eslint` 0 错误、`oxlint` 0 警告

**写库链路验证**（模拟 `CrudService.filterWritable` 真实行为）：

- 分类提交 `tag=热销 / enabled=1 / type=TAB / sort=99` -> 白名单全部放行，落库后可回读
- 分账提交 `investorThresholdAmount=100000 / investorRatioAfter=2000` -> 落库后可回读
- 上述验证在事务内完成并回滚，生产数据未被污染

> 说明：阶段 2 是关键补丁。阶段 1 仅「加列」时，页面提交的 `tag`/`enabled`
> 仍会被 `CrudService.filterWritable()` 静默丢弃；阶段 2 放行白名单后写入才真正生效。

---

### 阶段 2：后端白名单放行新字段

#### 步骤 2.1 同步 `CrudRegistry`

文件：`server/src/main/java/com/wuling/system/crud/CrudRegistry.java`

- `productCategories`：`writable` 由 `parent_id, code, name, type, sort`
  改为 `parent_id, code, name, tag, type, sort, enabled`；`filterable` 补 `enabled`
- `splitRules`：`writable` 补 `investor_threshold_amount`、`investor_ratio_after`

> 这是本轮的关键：字段加了列但不在白名单里，仍会被 `filterWritable()` 静默丢弃。

### 阶段 3：前端页面与新字段对齐

#### 步骤 3.1 「分类管理」页

文件：`src/views/product/category/index.vue`

- 列：`编码/名称/分类标签/排序/状态`（状态用 `enabled`，`1/0 → 启用/停用` 映射）
- 搜索：状态 select 的 value 用 `1`/`0`，并按 `filterable` 走等值过滤
- 表单：补 `type`（select：`TAB/GROUP/CATEGORY`，新增必填）+ `tag` + `enabled`
- 修「排序全 0」：`sort` 必填并给默认值

#### 步骤 3.2 「分账规则」页

文件：`src/views/product/split/index.vue`

- 列改名：`门店比例/资源方比例/投资人比例`，值 = `ratio/100` 显示为百分比
- `作用范围` 中文映射：`GLOBAL → 全局`、`PRODUCT → 商品`
- 搜索「范围」select 的 value 改为 `GLOBAL`/`PRODUCT`（当前是小写中文，恒查不到）
- 表单：五方比例（万分比）+ `investorThresholdAmount` + `investorRatioAfter`
- 校验：五方 `platform+store+channel+investor+supplier = 10000`（与 `SplitCalculator` 一致）
- 启用/停用统一走 `patch`（真写库），移除本地版 `enableSplitRule`

### 阶段 4：消灭「假写库」并让写库可见

#### 步骤 4.1 修纯本地动作

- `store/modules/admin/index.ts`：`toggleFeature`、`enableSplitRule` 改为先调后端、
  成功后再改本地镜像，失败抛错（与阶段 2 的 `add/update/patch` 一致）
- 逐个核对 `REMOTE_RESOURCES` 内的资源是否存在同类本地动作

#### 步骤 4.2 补审计与静默丢弃告警

- 后端 `CrudService` create/update/delete 落 `audit_log`
  （记录 operator/module/action/target/before/after/reason）
- 前端在写操作后，对**白名单外字段**给出显式提示，避免再次静默丢弃

### 阶段 5：分账引擎接入投资人阈值

文件：`server/src/main/java/com/wuling/finance/service/LedgerService.java`

- 计算 `investor` 前，先查该投资人**当月累计已分账金额**（`split_snapshot` 口径）
- 若 `investor_threshold_amount > 0` 且累计额 ≥ 阈值 → 用 `investor_ratio_after` 替代 `investor_ratio`
- 比例替换后需保证五方合计仍为 10000：差额从 `platform_ratio` 增减（平台为尾差方）
- `SplitCalculator.calc()` 增加「有效投资人比例」入参，保持纯函数、便于单测

> 该步骤涉及资金计算，必须补单测后再上线。

---

## 五、验收标准

| 项 | 验收方式 |
|----|---------|
| **字段已入库** | `SHOW COLUMNS` 可见 `tag`/`enabled`/`investor_threshold_amount`/`investor_ratio_after` |
| 分类新增/编辑 | 落库后 `SELECT * FROM product_category` 可见 `tag`/`enabled`/`sort` 为表单值 |
| 分类状态查询 | 选「启用」只返回 `enabled=1`，选「停用」只返回 `enabled=0` |
| 分账规则显示 | 列表门店比例为 `50%`（5000 万分比），范围为「全局」而非 `GLOBAL` |
| 分账新增/编辑 | `investor_threshold_amount`/`investor_ratio_after` 落库 |
| 五方合计校验 | 合计非 10000 时前端拦截，后端 `SplitCalculator` 也抛错 |
| 全量按钮 | 每个写操作后查库，字段值与界面一致 |
| 审计 | `SELECT COUNT(*) FROM audit_log WHERE module != "AUTH"` 随操作增长 |
| 投资人阈值 | 单测覆盖「未达标」「达标当单」「达标后」三种场景 |

---

## 六、风险与注意

1. **迁移脚本不在本地执行**：本地 `flyway.enabled=false`，切勿手工在生产跑未经评审的 DDL。
2. **`ADD COLUMN` 幂等性**：MySQL 不支持 `ADD COLUMN IF NOT EXISTS`；
   若目标库已含该列会报 `Duplicate column name`，执行前先用 `SHOW COLUMNS` 确认。
3. **`sort = id` 回填**：仅对当前 `sort=0` 的历史数据，避免影响已手工排序的数据。
4. **`type` NOT NULL**：前端表单必须补该字段，否则新增仍会失败。
5. **资金口径变更需谨慎**：阶段 5 影响真实分账金额，须先单测、再灰度。
6. **`investor_ratio_after` 回填**：默认等于 `investor_ratio` 且阈值为 0，
   相当于「不启用」，保证上线前后分账行为完全一致。

---

## 七、生产部署记录（2026-09-25）

服务器：`43.136.91.239`（CentOS，Docker 部署）

### 部署内容

| 项 | 内容 |
|----|------|
| 后端镜像 | `wuling/server:20260924-235934`（tag 为 `local`） |
| 前端 | `prod-ip` 模式构建，部署至 `/opt/wuling/web` |
| 数据库 | V30 字段已存在（早前手工执行） |

### 回滚点

| 项 | 位置 |
|----|------|
| 旧后端镜像 | `wuling/server:pre-v30-20260924-235528` |
| 旧前端目录 | `/opt/wuling/web.prev-20260925-000636` |
| 旧 jar | `/opt/wuling/build/server/target/server.jar.bak-20260924-235528` |

### 部署中遇到并解决的两个问题（重要，供后人参考）

#### 问题 A：Flyway 将 V30 标记为失败，导致服务无法启动

**现象**：新容器启动即退出，日志报：

```
Detected failed migration to version 30 (category and split rule enhance).
Please remove any half-completed changes then run repair to fix the schema history.
```

**根因**：本项目采用「人工执行 DDL」的方式加了 V30 的列（见阶段 1 记录）。
之后服务启动时 Flyway 仍会尝试执行 V30 脚本，因列已存在而报 `Duplicate column name`，
于是把该条历史记为 `success=0`。此后每次启动都因「存在失败迁移」而拒绝启动。

**处理**：由于 schema 已完全符合 V30 意图（4 个列均已存在），
把 `flyway_schema_history` 中 V30 的 `success` 修正为 1（等价于官方 `flyway repair`，只修历史表、不动 schema）：

```sql
update wuling.flyway_schema_history set success=1 where version='30' and success=0;
```

**教训**：手工执行 DDL 后，务必同步在 Flyway 历史表中登记（或直接让 Flyway 执行迁移），
否则「人工改库」与「迁移框架」会互相打架。

#### 问题 B：systemd 旧单元抢占 8090 端口

**现象**：`docker compose up` 报 `listen tcp4 127.0.0.1:8090: bind: address already in use`。

**根因**：服务器上存在历史遗留的 systemd 单元 `wuling-server`（`disabled` 但非 stopped）。
该单元用 `/opt/wuling/app/server/server.jar`（旧 jar）启动 8090。
Flyway 报错修复后，它竟成功启动并占用了端口，与 Docker 容器冲突。

**处理**：`systemctl stop + disable wuling-server`，单元文件保留以备回滚。

**教训**：同一服务不应同时存在 systemd 与 Docker 两套运行方式，
部署前应先确认「谁在监听目标端口」。

### 生产验证结果

| 验证项 | 结果 |
|--------|------|
| 内置冒烟测试 `deploy-backend.sh verify` | 通过 7 项，失败 0 项 ✅ |
| 容器健康 | `wuling-server` healthy ✅ |
| 问题一：tag/enabled 字段 | 新增/编辑均落库并回读一致 ✅ |
| 问题一：状态等值查询 | `enabled=1` → 8 条；`enabled=0` → 0 条（正确区分）✅ |
| 问题二：作用范围中文 | GLOBAL→全局、PRODUCT→商品 ✅ |
| 问题二：三列比例 | 50%/15%、45%/10%、45%/25% 正常显示 ✅ |
| 问题二：范围等值查询 | GLOBAL→1 条、PRODUCT→2 条 ✅ |
| 问题三：审计留痕 | 新增/编辑/删除均入库，`operator=super` 正确 ✅ |
| 前端资源 | 首页与两个新页面 bundle 均 HTTP 200 ✅ |

### 遗留项处理记录（2026-09-25）

#### 1. 历史前端备份目录清理 ✅

- 删除 20 个历史目录（`web.old-*` 19 个 + `web.bak-20260923230151`），约 80MB
- 删除前校验：每个目录均含 `index.html` + `assets`（确认为前端产物）、
  无 nginx 配置引用、无进程占用
- **保留**：`web`（运行中）、`web.prev-20260925-000636`、`web.bak-20260924-235528`（回滚点）

#### 2. systemd 与 Docker 双轨运行消除 ✅

**问题**：服务器上有 8 个历史 systemd 单元（`wuling-{gateway,auth-service,...}`），
均为 `disabled` 且带 `Restart=always`。部署时 `wuling-server` 单元竟被拉起，
用旧 jar 抢占 8090 端口，导致新 Docker 容器无法启动。

**为何单纯 disable 不够**：`disable` 只影响开机自启，挡不住 `auto-restart` 状态
（单元进入失败循环后会持续重试），也挡不住手动 `systemctl start`。

**处理（三层防护）**：

1. `systemctl stop` + `disable`，清掉 auto-restart 状态
2. 单元文件加 `ConditionPathExists=/etc/wuling-docker-managed`
   （该文件**故意不创建**），使任何启动尝试都被 systemd 条件判断拦下；
   单元文件保留，便于日后回滚
3. 单元文件备份至 `/opt/wuling/backup/systemd-units-20260925-090238/`

**验证**：`systemctl start wuling-server` 被拦下（`is-active: unknown`），
8 个端口全部由 `docker-proxy` 持有，20 秒观察窗口内无新的拉起尝试。

#### 3. Docker 镜像清理 ✅（顺带处理）

发现 102 个镜像、11.96GB 可回收（80%），主要是历史构建 tag 堆积。
按保守策略清理（保留在用镜像 + `local`/`latest` + 本次与回滚点）：

| 指标 | 清理前 | 清理后 |
|------|--------|--------|
| 镜像数 | 102 | 17 |
| 镜像占用 | 14.88GB | 3.713GB |

**保留的关键回滚镜像**：

- `wuling/server:pre-v30-20260924-235528`（本次部署前的版本）
- `wuling/server:20260924-235934`（本次部署的版本）

#### 最终状态

| 验证项 | 结果 |
|--------|------|
| 后台首页 | HTTP 200 ✅ |
| 分类页 / 分账页 bundle | HTTP 200 ✅ |
| 小程序菜单 API | HTTP 200 ✅ |
| 网关健康 | HTTP 200 ✅ |
| 12 个容器 | 全部运行，`wuling-server` / `wuling-product-service` / `wuling-mysql` / `wuling-rabbitmq` healthy ✅ |
| nginx | active ✅ |
