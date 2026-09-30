# 活动城市（activity_city）与门店绑定改造实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 在 `region`（全国 337 个行政区划市）之上新增一层「活动城市」作为运营白名单。只有活动城市才会在小程序端出现；门店必须绑定活动城市；用户定位城市未开通时回落到默认城市。

**Architecture:** `region` 保持只读基础数据（含全部省市区）。新增 `activity_city` 表承载运营可编辑的开城白名单。`store_profile` 新增 `activity_city_id` 外键强绑定。小程序 `GET /api/v1/app/config/cities` 由「查 region level=2」改为「查 activity_city 且 status=enabled」；`GET /api/v1/app/stores` 增加「所属城市必须是活动城市」过滤。`store_profile.city/city_code` 文本列标记废弃。

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / JdbcTemplate / Flyway / MySQL；Vue 3 + TypeScript + Naive UI (soybean-admin)；微信小程序原生。

**Spec:** 用户需求（本会话）：城市表数据（region）之上再加一层活动城市；只有活动城市才在小程序端显示；门店绑定活动城市；小程序端获取城市信息也要调整。已确认决策：① 活动城市从全国城市中勾选新增；② 门店用外键强约束（口径 1）；③ 现有有门店的城市初始化为活动城市；④ 定位城市未开通时回落到默认城市；⑤ `store_profile.city/city_code` 标记废弃。

## Global Constraints

- `region` 表**保持只读**，业务不直接编辑；活动城市写在 `activity_city`。
- 门店与活动城市为**外键强绑定**（`store_profile.activity_city_id`），`city/city_code` 文本列标记废弃但暂不删列（兼容回滚）。
- 小程序城市接口**不返回**非活动城市，且不得出现「有门店但城市未开通」的矛盾。
- 现有数据**平滑迁移**：把当前已有门店的城市自动初始化为活动城市，避免小程序选城列表立即变空。
- 每个任务结束独立可验证；每任务一次提交。

---

## 背景：与既有计划的关系

`docs/superpowers/plans/2026-09-28-sales-city-management.md` 曾设计过 `sales_city` 表（`region` 降级候选库），但**从未落地**——全部 migration 中搜不到 `sales_city`，该计划只写到 Task 4 即中止。

本计划与它的差异（以本计划为准）：

| 维度       | 原 sales_city 计划                   | 本 activity_city 计划                  |
| ---------- | ------------------------------------ | -------------------------------------- |
| 表名       | `sales_city`                         | `activity_city`                        |
| 门店关联   | `cityCode` 文本冗余                  | `activity_city_id` 外键强绑定          |
| 省/市编码  | `province_code` + `city_code` 字符串 | `region_id` 主键关联 + 冗余 code/name  |
| 直辖市合并 | 合并为单条                           | 沿用 `region` level=2 现状，不特殊处理 |

---

## 现状与风险基线（已实测确认）

- **城市接口返回全量**：`/api/v1/app/config/cities` 实测返回 **337 个城市**（全量行政区划），小程序端把未开通城市也暴露给用户 —— 本次核心要修。
- **后端实现位置**：`server/src/main/java/com/wuling/system/controller/AppConfigController.java#cities()`，SQL 为 `from region c ... where c.level = 2 and c.deleted = 0 and c.status = 'enabled'`。
- **`region.status` 已存在**（V65 迁移新增，`enabled/disabled`），本计划**不重复加列**。
- **门店城市已是外键**：`store_profile.city_id`（Long，关联 `region.id`，V67 迁移新增）已实现门店与城市强关联；`city` 为冗余展示文本，`city_code` 由 `resolveCityCode(city_id)` 从 region 反查（非存储列）。本次在 `city_id` 基础上再加 `activity_city_id` 关联活动城市。
- **门店接口**：`AppStoreController#stores()` → `StoreService.listAppStores()`，无城市过滤。
- **小程序读城市**：`user-h5/utils/store.js#refreshCitiesFromRemote`，经 `api.fetchCities()` 拿 `/app/config/cities`。
- **默认城市硬编码**：`DEFAULT_CITY_CODE = '4301'`（长沙），回落逻辑已存在（`user-h5/utils/store.js`）。
- **后台城市管理页**：`src/views/system/city/index.vue`，直接读写 `region`（`store.queryRemote('cities', {...search, eq_level: '2'})`），CRUD 白名单在 `server/src/main/java/com/wuling/system/crud/CrudRegistry.java` 的 `cities` 资源。
- **小程序端必须配套调整**：`city-picker` 按 `provinceName` 分组渲染；`city-picker.js` 依赖 `getCityList()` 与 `selectCity()`。

---

## 任务总览（严格按序执行）

1. Task 1：V71 迁移 —— 新增 `activity_city` 表 + 初始化现有门店城市 + 门店加外键
2. Task 2：后端 —— `cities` 接口改查活动城市
3. Task 3：后端 —— `stores` 接口加活动城市过滤
4. Task 4：后端 —— `activity_city` CRUD 资源 + 排除 `cities` 旧资源写入口
5. Task 5：后台 —— 城市管理页改读 `activity_city`（从 region 勾选新增）
6. Task 6：后台 —— 门店表单城市改为活动城市下拉
7. Task 7：小程序 —— 城市数据结构适配 + 空态与回落验证
8. Task 8：全链路回归验证

---

## Task 1: V71 迁移 —— activity_city 表 + 初始化 + 门店外键

**Files:**

- Create: `server/src/main/resources/db/migration/V71__activity_city.sql`

**Interfaces:**

- Consumes: 现有 `region` 表（level=2 的市）、`store_profile` 表。
- Produces: 表 `activity_city(id, region_id, city_code, city_name, province_id, status, sort, open_time, remark, create_time, update_time, deleted)`；列 `store_profile.activity_city_id`。

- [x] **Step 1: 写迁移**

创建 `server/src/main/resources/db/migration/V71__activity_city.sql`：

```sql
-- =====================================================================
-- V71：活动城市（运营开城白名单）与门店强绑定
--
-- 背景：region 是行政区划基础数据（全国 337 个市），不具备「是否开通运营」
--   语义；小程序端此前把全国城市都暴露给用户。本迁移在 region 之上加一层
--   活动城市白名单，只有活动城市才在小程序端显示。
--
-- 决策：
--   · region 保持只读基础数据，活动城市独立建表；
--   · 门店通过 activity_city_id 外键强绑定活动城市；
--   · store_profile.city / city_code 文本列标记废弃（暂不删，兼容回滚）；
--   · 初始化：把当前已有门店的城市全部落为活动城市，避免小程序选城变空。
-- =====================================================================

CREATE TABLE IF NOT EXISTS activity_city (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    region_id   BIGINT UNSIGNED NOT NULL COMMENT '关联 region.id（level=2 的市）',
    city_code   VARCHAR(32)  NOT NULL COMMENT '城市编码（冗余 region.code，便于查询）',
    city_name   VARCHAR(64)  NOT NULL COMMENT '城市名（冗余 region.name，便于展示）',
    province_id BIGINT UNSIGNED NULL COMMENT '所属省 region.id',
    status      VARCHAR(16)  NOT NULL DEFAULT 'enabled' COMMENT 'enabled启用 disabled停用',
    sort        INT          NOT NULL DEFAULT 0,
    open_time   DATETIME     NULL COMMENT '开城时间',
    remark      VARCHAR(255) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_city_region (region_id, deleted),
    KEY idx_activity_city_status (status, sort),
    KEY idx_activity_city_code (city_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='活动城市（运营白名单）';

-- ---------- 初始化：当前已有门店的城市自动成为活动城市 ----------
-- 用 group by city_code 去重（多门店同城只落一条）；region_id 通过 code 反查。
INSERT INTO activity_city (region_id, city_code, city_name, province_id, status, sort)
SELECT r.id, r.code, r.name, r.parent_id, 'enabled', r.sort
FROM region r
WHERE r.level = 2
  AND r.deleted = 0
  AND r.code IN (
      SELECT DISTINCT sp.city_code FROM store_profile sp
      WHERE sp.deleted = 0 AND sp.city_code IS NOT NULL AND sp.city_code <> ''
  )
ON DUPLICATE KEY UPDATE city_name = VALUES(city_name);

-- ---------- 门店加活动城市外键 ----------
SET @s := (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE store_profile ADD COLUMN activity_city_id BIGINT UNSIGNED NULL COMMENT ''绑定的活动城市 id（强约束）''',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'store_profile' AND column_name = 'activity_city_id');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 按旧 city_code 文本回填门店的活动城市绑定
UPDATE store_profile sp
JOIN activity_city ac ON ac.city_code = sp.city_code AND ac.deleted = 0
SET sp.activity_city_id = ac.id
WHERE sp.deleted = 0 AND sp.activity_city_id IS NULL;

ALTER TABLE store_profile
    ADD INDEX idx_store_profile_activity_city (activity_city_id);
```

- [x] **Step 2: 本地执行迁移并核对**

预期：`activity_city` 表建立；`select count(*) from activity_city` ≥ 1（至少长沙）；`store_profile.activity_city_id` 全部回填非空。

- [x] **Step 3: 提交**

```bash
git add server/src/main/resources/db/migration/V71__activity_city.sql
git commit -m "feat(db): V71 活动城市表与门店强绑定"
```

---

## Task 2: 后端 —— cities 接口改查活动城市

**Files:**

- Modify: `server/src/main/java/com/wuling/system/controller/AppConfigController.java`（`cities()` 方法）

**Interfaces:**

- Produces: `GET /api/v1/app/config/cities` 返回**仅** `activity_city.status='enabled'` 且 `deleted=0` 的城市；响应字段保持不变（`id/code/name/parentId/provinceName/latitude/longitude`），其中 `id` 仍为 `region.id` 以保持门店 `city_id` 关联口径。

- [x] **Step 1: 改 SQL**

把 `cities()` 的数据源从 `region` 换成 `activity_city` join `region`：

```java
@GetMapping("/cities")
public Result<Object> cities() {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "select r.id, r.parent_id, r.code, r.name, r.latitude, r.longitude, p.name as province_name "
                    + "from activity_city ac "
                    + "join region r on r.id = ac.region_id and r.deleted = 0 "
                    + "left join region p on p.id = r.parent_id and p.deleted = 0 "
                    + "where ac.status = 'enabled' and ac.deleted = 0 "
                    + "order by ac.sort asc, r.sort asc, r.id asc");
    // 下方字段拼装逻辑不变
}

```

- [x] **Step 2: 验证**

调用 `GET /api/v1/app/config/cities`，预期返回条目数**远小于 337**（等于活动城市数），且包含长沙。

- [x] **Step 3: 提交**

```bash
git commit -am "feat(server): 小程序城市接口改读活动城市白名单"
```

---

## Task 3: 后端 —— stores 接口加活动城市过滤

**Files:**

- Modify: `server/src/main/java/com/wuling/subject/service/StoreService.java`（`listAppStores()`）

**Interfaces:**

- Produces: `GET /api/v1/app/stores` 只返回 `activity_city_id` 指向 enabled 活动城市的门店，杜绝「有门店但城市未开通」。

- [x] **Step 1: 加过滤条件**

在 `listAppStores()` 的查询中追加：

```sql
inner join activity_city ac on ac.id = sp.activity_city_id
   and ac.deleted = 0 and ac.status = 'enabled'
```

- [x] **Step 2: 验证**

调用 `GET /api/v1/app/stores`，确认返回门店的城市均在活动城市列表中。

- [x] **Step 3: 提交**

```bash
git commit -am "feat(server): 门店接口仅返回活动城市门店"
```

---

## Task 4: 后端 —— activity_city CRUD 资源

**Files:**

- Modify: `server/src/main/java/com/wuling/system/crud/CrudRegistry.java`

**Interfaces:**

- Produces: CRUD 资源 `activityCities`（表 `activity_city`，可写 `region_id/city_code/city_name/status/sort/remark`）。
- **同时**：把 `cities` 资源的可写字段收窄或移出（避免后台继续直改 `region` 造成双源）。

- [x] **Step 1: 注册资源**

```java
Map.entry("activityCities", new Resource("activityCities", "activity_city",
        List.of("region_id", "city_code", "city_name", "province_id", "status", "sort", "remark"),
        List.of("city_name", "city_code"), "sort asc, id asc",
        List.of("status"))),
```

- [x] **Step 2: 收窄旧 cities 资源**

把 `cities` 资源从**可写**降级为**只读**（或直接移除写能力），确保后台城市管理只经 `activity_city`。

- [x] **Step 3: 验证 + 提交**

```bash
git commit -am "feat(server): 新增 activityCities CRUD 资源并收窄 region 写入口"
```

---

## Task 5: 后台 —— 城市管理页改读活动城市

**Files:**

- Modify: `src/views/system/city/index.vue`
- Test: `user-h5/scripts/acceptance-check.test.mjs`（如有城市相关断言则同步）

**Interfaces:**

- Consumes: CRUD 资源 `activityCities`（Task 4）。
- Produces: 城市管理页列表 = 活动城市；「新增城市」从 `region` level=2 中**选**一个市，落库到 `activity_city`。

- [x] **Step 1: 改数据源**

```ts
const config: AdminListConfig = {
  title: '活动城市管理',
  remoteKey: 'activityCities',
  remoteDeps: ['provinces'],
  // ...
  loadData: async ({ page, pageSize, search }) =>
    store.queryRemote('activityCities', { ...search }, page, pageSize),
```

- [x] **Step 2: 新增表单改为「选城市」**

「城市」字段由手填文本改为下拉：选项来自 `region` level=2 全量城市（复用现有 `cities` 只读查询），选中后把 `region_id / city_code / city_name / province_id` 一并写入。

```ts
const cityOptions = async () =>
  (await store.queryRemote('cities', { eq_level: '2' }, 1, 1000)).records.map((c: any) => ({
    label: `${c.name}（${c.code}）`,
    value: String(c.id)
  }));
```

- [x] **Step 3: 状态/排序/备注**

沿用现有启用/停用交互，写入 `activity_city.status`；补「备注」列可选。

- [x] **Step 4: 验证 + 提交**

手工验证：新增一个活动城市 → 小程序 `/config/cities` 立即能查到；停用 → 小程序不再返回。

```bash
git commit -am "feat(admin): 城市管理改为维护活动城市白名单"
```

---

## Task 6: 后台 —— 门店表单城市改活动城市下拉

**Files:**

- Modify: 门店管理页（`src/views/subject/store/index.vue` 或对应门店表单）

**Interfaces:**

- Consumes: `activityCities`（enabled 列表）。
- Produces: 门店表单的「城市」为下拉，值为 `activity_city.id`，写入 `store_profile.activity_city_id`。

- [x] **Step 1: 门店城市字段改下拉**

```ts
const activityCityOptions = async () =>
  (await store.queryRemote('activityCities', { eq_status: 'enabled' }, 1, 500)).records.map((c: any) => ({
    label: c.cityName,
    value: String(c.id)
  }));
```

- [x] **Step 2: 保存时写 activity_city_id**

payload 增加 `activity_city_id`；`city`/`city_code` 文本字段标记废弃（保留写入以兼容旧接口，或直接停写，视回归结果定）。

- [x] **Step 3: 验证 + 提交**

手工验证：门店只能选择活动城市；绑定后 `/app/stores` 正常返回。

```bash
git commit -am "feat(admin): 门店绑定活动城市"
```

---

## Task 7: 小程序 —— 城市信息适配

**Files:**

- Modify: `user-h5/utils/store.js`（`refreshCitiesFromRemote` 的字段映射保持不变，重点验证）
- Modify: `user-h5/pages/city-picker/city-picker.js`（如分组依赖 `provinceName`）
- Test: `user-h5/scripts/store-selection.test.mjs`、`store-picker-page.test.mjs`

**Interfaces:**

- 小程序拿到的是**已经过滤过的活动城市**，前端无需再过滤；但必须处理「活动城市为空」与「定位城市不在列表中」。

- [x] **Step 1: 确认字段兼容**

`refreshCitiesFromRemote` 已读取 `id/code/name/provinceName/parentId/latitude/longitude` —— 与 Task 2 的响应结构一致，**无需改动**。核对该函数在列表为空时不会覆盖 `cityCatalog` 为 `[]`（当前实现有 `if (Array.isArray(list) && list.length)` 守卫，保留）。

- [x] **Step 2: 定位回落**

确认 `resolveLocationContext` 在「定位城市不在活动城市列表」时回落到 `DEFAULT_CITY_CODE = '4301'`；若长沙未开通，则回落到 `cityCatalog[0]`。补一条显式兜底测试。

- [x] **Step 3: city-picker 空态**

活动城市为空时展示空态（复用 `empty-state` 组件），不得渲染空白页。

- [x] **Step 4: 验证 + 提交**

```bash
cd user-h5 && npm run check
git commit -am "feat(miniapp): 城市列表适配活动城市白名单"
```

---

## Task 8: 全链路回归验证

**Files:**

- Test: `user-h5/scripts/store-selection.test.mjs`、`user-h5/scripts/store-id-contract.test.mjs`、`user-h5/scripts/acceptance-check.test.mjs`

- [x] **Step 1: 小程序全量校验**

```bash
cd user-h5 && npm run check
```

- [x] **Step 2: 四端联调验收**

| 场景               | 预期                                   |
| ------------------ | -------------------------------------- |
| 后台新增活动城市   | 小程序 `/config/cities` 立即返回该城市 |
| 后台停用活动城市   | 小程序不再返回；该城市门店也不再返回   |
| 门店绑定非活动城市 | 后台保存被拒绝（下拉根本选不到）       |
| 定位到未开通城市   | 回落到默认城市（长沙 4301）            |
| 活动城市为空       | `city-picker` 展示空态，不白屏         |
| 门店接口           | 返回门店的城市必在活动城市列表内       |

- [x] **Step 3: 提交**

```bash
git commit -am "test: 活动城市全链路回归"
```

---

## 风险与回滚

| 风险                                                 | 缓解                                                                                                                   |
| ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| 初始化遗漏导致小程序选城变空                         | Task 1 用 `store_profile.city_code` 反查初始化；上线前先跑 `select count(*) from activity_city` 确认覆盖全部有门店城市 |
| `region.status` 与 `activity_city.status` 双状态混淆 | `region.status` 只管行政区划基础数据；小程序只看 `activity_city.status`，文档与后台文案区分                            |
| `store_profile.city/city_code` 废弃后仍有旧代码读取  | 分批：先加 `activity_city_id` 并双写，观察一版后再停写文本列，最后才考虑删列                                           |
| 回滚                                                 | 迁移只做「加表 + 加列 + 回填」，不删列不改类型，回滚只需改回 `AppConfigController#cities()` 的 SQL                     |
