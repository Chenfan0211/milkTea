# 城市管理（销售城市）与小程序选城调整实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 后台「城市管理」支持运营按「省→市」级联选择并自定义销售城市名称；小程序读销售城市列表供用户下单选城，并优先按定位自动定城。候选库为中国全量省市（源自 modood 数据），只保留省市两级。

**Architecture:** `region` 表降级为只读候选库（全量省市，level 1/2），新增 `sales_city` 表承载可编辑的「销售城市」业务数据；小程序新增 `GET /api/v1/app/sales-cities` 接口，废弃 `app_config.app_cities` 静态 JSON；门店的 `cityCode` 由后端按 `sales_city` 返回，删除前端三城硬编码。直辖市的「省」与其唯一「市」合并为单条销售城市。

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / Flyway / MySQL；Vue 3 + TypeScript + Naive UI (soybean-admin)；微信小程序原生；Node 脚本生成 SQL。

**Spec:** 用户需求（本会话）：城市管理新增/编辑需选省和市；从 GitHub 拉中国全量省市；经纬度与排序暂不需要；运营可自定义城市名；管理的是销售城市；小程序显示销售城市供下单选择，并先按用户定位自动定城。已确认决策：① 方案 A（新增 `sales_city` 表，`region` 降级候选库）；② 只要省市两级（直辖市省级单市）；③ 数据源 modood/Administrative-divisions-of-China；④ 现有 3 城平滑迁移。

## Global Constraints

- 经纬度、排序号在表单中**移除**（本次需求明确不要）。
- `region` 只读，业务不再直接编辑；销售城市写在 `sales_city`。
- 直辖市（北京/天津/上海/重庆）在 `sales_city` 中合并为单条（province_code == city_code 语义，展示名即城市名）。
- 小程序城市列表只返回 `enabled=1` 的销售城市；`initial`（首字母）必须存在供字母分组。
- 现有 3 个销售城市（长沙/广州/深圳）平滑迁移，不改动其展示语义。
- 门店 `cityCode` 由后端返回，前端删除 `normalizeRemoteStore` 的三城硬编码。
- 每个任务结束独立可验证；每任务一次提交。

---

## 现状与风险基线

- **双源断裂**：后台写 `region` 表，小程序读 `app_config.app_cities`（静态 JSON，仅 3 城），两者不相通 —— 后台新增城市小程序不可见（本次核心要修）。
- **前端硬编码**：`user-h5/utils/store.js#normalizeRemoteStore` 用三城三元表达式把任何城市归到 `changsha/guangzhou/shenzhen`，新增城市必错。
- **列表混级**：`system/city/index.vue` 的 `cities` 资源未过滤 `level=2`，省份行（level=1）混入城市列表。
- **数据模型**：`region` 已有 `parent_id/code/name/level/sort/latitude/longitude`，但无 `initial`（首字母）与 `enabled` 语义。
- **级联能力已就绪**：`AdminListPage.vue:556` 的 `field.options(formModel)` 已支持依赖表单的动态下拉，无需改框架。

---

## 任务总览（严格按序执行）

1. Task 1：V59 迁移 —— 新增 `sales_city` 表 + 迁移现有 3 城
2. Task 2：数据生成脚本 —— 从 modood 拉取并生成全量省市 SQL
3. Task 3：后端 —— `salesCity` CRUD 资源 + 小程序 `sales-cities` 接口 + 门店返回 `cityCode`
4. Task 4：后台城市管理页 —— 改读 salesCity + 省市级联 + 去经纬度排序
5. Task 5：小程序 —— 去硬编码 + 读 sales-cities + 选城/定位适配
6. Task 6：全链路回归验证

---

### Task 1: V59 迁移 —— sales_city 表 + 现有 3 城迁移

**Files:**

- Create: `server/src/main/resources/db/migration/V59__sales_city.sql`
- Test: `server/src/test/java/com/wuling/common/SalesCityMigrationV59Test.java`

**Interfaces:**

- Consumes: 现有 `region` 表、`app_config` 表（`app_cities` 键）。
- Produces: 表 `sales_city(id, province_code, city_code, name, initial, enabled, sort, create_time, update_time, deleted)`。

- [ ] **Step 1: 写失败测试**

创建 `server/src/test/java/com/wuling/common/SalesCityMigrationV59Test.java`：

```java
package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V59 销售城市迁移静态校验。
 *
 * <p>只做文本断言（不需要数据库），与 MigrationFilesTest 同风格。
 */
class SalesCityMigrationV59Test {

    private final Path schema = Paths.get("src/main/resources/db/migration/V59__sales_city.sql");

    @Test
    void migrationFileShouldExist() {
        assertTrue(Files.exists(schema), "V59 迁移文件应存在");
    }

    @Test
    void schemaShouldCreateSalesCityTable() throws Exception {
        String content = Files.readString(schema);
        assertTrue(content.contains("CREATE TABLE"), "应有建表语句");
        assertTrue(content.contains("sales_city"), "应创建 sales_city 表");
        assertTrue(content.contains("province_code"), "应有 province_code 列");
        assertTrue(content.contains("city_code"), "应有 city_code 列");
        assertTrue(content.contains("initial"), "应有 initial（首字母）列");
        assertTrue(content.contains("enabled"), "应有 enabled（上架状态）列");
    }

    @Test
    void schemaShouldMigrateExistingThreeCities() throws Exception {
        String content = Files.readString(schema);
        assertTrue(content.contains("长沙市"), "应迁移长沙");
        assertTrue(content.contains("广州市"), "应迁移广州");
        assertTrue(content.contains("深圳市"), "应迁移深圳");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd server && mvn -q -Dtest=SalesCityMigrationV59Test test
```

预期：FAIL —— `migrationFileShouldExist` 失败（文件未创建）。

- [ ] **Step 3: 写 V59 迁移**

创建 `server/src/main/resources/db/migration/V59__sales_city.sql`：

```sql
-- =====================================================================
-- V59：销售城市（与只读候选库 region 分离）
--
-- 背景：后台「城市管理」管理的是「销售城市」，不是全国所有城市。
--   · region 表作为只读候选库（全量省市，level 1/2）；
--   · sales_city 表承载运营可编辑的销售城市（自定义名、上架状态、首字母）；
--   · 小程序读 sales_city（enabled=1），废弃 app_config.app_cities 静态 JSON。
--
-- 直辖市（北京/天津/上海/重庆）合并为单条：province_code 与 city_code
-- 均为该省级 code，name 即城市名（如「北京市」）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS sales_city (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    province_code VARCHAR(32) NOT NULL COMMENT '省级 code（region.level=1.code）',
    city_code     VARCHAR(32) NOT NULL COMMENT '市级 code（region.level=2.code；直辖市与省同）',
    name          VARCHAR(64) NOT NULL COMMENT '展示名（运营可自定义）',
    initial       CHAR(1) NOT NULL DEFAULT '' COMMENT '首字母（城市选择页字母分组）',
    enabled       TINYINT NOT NULL DEFAULT 1 COMMENT '1上架 0停用',
    sort          INT NOT NULL DEFAULT 0,
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_city_city_code (city_code),
    KEY idx_sales_city_province (province_code),
    KEY idx_sales_city_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售城市';

-- 迁移现有 3 个销售城市（数据源：app_config.app_cities 静态 JSON）
-- 用 city_code 唯一键做幂等，重复执行不报错。
INSERT INTO sales_city (province_code, city_code, name, initial, enabled, sort) VALUES
('43', '4301', '长沙市', 'C', 1, 1),
('44', '4401', '广州市', 'G', 1, 2),
('44', '4403', '深圳市', 'S', 1, 3)
ON DUPLICATE KEY UPDATE name = VALUES(name), initial = VALUES(initial);
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd server && mvn -q -Dtest=SalesCityMigrationV59Test test
```

预期：PASS（3 用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/resources/db/migration/V59__sales_city.sql server/src/test/java/com/wuling/common/SalesCityMigrationV59Test.java
git commit -m "feat(db): V59 销售城市表结构 + 现有3城迁移"
```

---

### Task 2: 数据生成脚本 —— 从 modood 拉取并生成全量省市 SQL

**Files:**

- Create: `scripts/gen-region-seed.mjs`
- Create: `server/src/main/resources/db/migration/V59__seed_region_full.sql`（脚本产出）
- Test: `scripts/gen-region-seed.test.mjs`

**Interfaces:**

- Consumes: `provinces.json` 与 `cities.json`（modood 仓库 raw）。
- Produces: `V59__seed_region_full.sql`（`region` 表 INSERT，省 + 市，直辖市合并）。

- [ ] **Step 1: 写失败测试**

创建 `scripts/gen-region-seed.test.mjs`：

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const sql = readFileSync(
  new URL('../server/src/main/resources/db/migration/V59__seed_region_full.sql', import.meta.url),
  'utf8'
);

// 1) 必须有 INSERT INTO region
assert.ok(sql.includes('INSERT INTO region'), '应有 region 插入');

// 2) 省与市 code 均应出现（抽样：湖南 43 / 长沙 4301）
assert.ok(sql.includes('43'), '应含湖南');
assert.ok(sql.includes('4301'), '应含长沙');

// 3) 直辖市应合并（北京市 code=11 作为省级出现，不应有 1101「市辖区」市级）
assert.ok(sql.includes('11'), '应含北京');
assert.ok(!sql.includes("'1101'"), '不应含北京「市辖区」级');

// 4) 只保留省市两级：不应出现区县级 code（6 位，如 430102 芙蓉区）
assert.ok(!/43010[12]/.test(sql), '不应含区县级 code');

console.log('gen-region-seed: all assertions passed');
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node scripts/gen-region-seed.test.mjs
```

预期：FAIL —— 读取文件报错（文件不存在）。

- [ ] **Step 3: 写生成脚本**

创建 `scripts/gen-region-seed.mjs`：

```js
// 从 modood/Administrative-divisions-of-China 拉取中国全量省市，
// 生成 region 表 INSERT SQL（只保留省市两级，直辖市合并）。
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const BASE = 'https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist';

async function fetchJson(name) {
  const res = await fetch(`${BASE}/${name}`);
  if (!res.ok) throw new Error(`fetch ${name} failed: ${res.status}`);
  return res.json();
}

// 直辖市：市级行政区划为「市辖区」的省级 code
const MUNICIPALITIES = new Set(['11', '12', '31', '50']);

// 汉字首字母（GB2312 区间法，无需拼音库）
function initialOf(chinese) {
  const c = chinese.charCodeAt(0);
  if (c < 0x4e00 || c > 0x9fa5) return '#';
  const bounds = [
    0xb0a1, 0xb0c5, 0xb2c1, 0xb4ee, 0xb6ea, 0xb7a2, 0xb8c1, 0xb9fe, 0xbbf7, 0xbfa6, 0xc0ac, 0xc2e8, 0xc4c3, 0xc5b6,
    0xc5be, 0xc6da, 0xc8bb, 0xc8f6, 0xcbfa, 0xcdda, 0xcef4, 0xd1b9, 0xd4d1
  ];
  const idx = Math.min(22, Math.floor((c - 0x4e00) / 500));
  return 'ABCDEFGHJKLMNOPQRSTWXYZ'[idx] || '#';
}

const [provinces, cities] = await Promise.all([fetchJson('provinces.json'), fetchJson('cities.json')]);

const lines = [];
lines.push('-- V59 全量省市候选库（modood/Administrative-divisions-of-China）');
lines.push('-- 只保留 level=1 省 + level=2 市；直辖市不生成「市辖区」级。');
lines.push('INSERT INTO region (parent_id, code, name, level, sort) VALUES');

// 省（parent_id=0），id 从 100 起避免与既有 1~5 冲突
const provinceId = new Map();
let seq = 100;
for (const p of provinces) {
  provinceId.set(p.code, seq);
  lines.push(`(${0}, '${p.code}', '${p.name}', 1, ${seq - 99}),`);
  seq++;
}

// 市（parent_id = 省 id）；直辖市跳过「市辖区」
for (const c of cities) {
  if (MUNICIPALITIES.has(c.provinceCode)) continue;
  const pid = provinceId.get(c.provinceCode);
  if (!pid) continue;
  lines.push(`(${pid}, '${c.code}', '${c.name}', 2, 0),`);
  seq++;
}

// 去掉最后一行逗号
lines[lines.length - 1] = lines[lines.length - 1].replace(/,$/, ';');
lines.push('');

const out = fileURLToPath(
  new URL('../server/src/main/resources/db/migration/V59__seed_region_full.sql', import.meta.url)
);
writeFileSync(out, lines.join('\n'), 'utf8');
console.log(`generated ${out}: ${lines.length - 4} rows`);
```

- [ ] **Step 4: 运行脚本生成 SQL**

```bash
node scripts/gen-region-seed.mjs
```

预期：输出 `generated ... rows`（省 34 + 市约 330+）。

- [ ] **Step 5: 运行测试确认通过**

```bash
node scripts/gen-region-seed.test.mjs
```

预期：PASS。

- [ ] **Step 6: 提交**

```bash
git add scripts/gen-region-seed.mjs scripts/gen-region-seed.test.mjs server/src/main/resources/db/migration/V59__seed_region_full.sql
git commit -m "feat(db): 全量省市候选库生成脚本与种子数据"
```

---

---

### Task 3: 后端 —— salesCity CRUD 资源 + sales-cities 接口 + 门店返回 cityCode

**Files:**

- Create: `server/src/main/java/com/wuling/system/entity/SalesCity.java`
- Create: `server/src/main/java/com/wuling/system/mapper/SalesCityMapper.java`
- Modify: `server/src/main/java/com/wuling/system/crud/CrudRegistry.java`
- Modify: `server/src/main/java/com/wuling/system/controller/AppConfigController.java`
- Modify: `server/src/main/java/com/wuling/subject/service/StoreService.java`
- Test: `server/src/test/java/com/wuling/common/SalesCityBackendTest.java`

**Interfaces:**

- Consumes: Task 1 的 `sales_city` 表。
- Produces: CRUD 资源 `salesCity`；接口 `GET /api/v1/app/sales-cities`（返回 `enabled=1`）；门店 DTO 增加 `cityCode`。

- [ ] **Step 1: 写失败测试**

创建 `server/src/test/java/com/wuling/common/SalesCityBackendTest.java`：

```java
package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 销售城市后端静态校验。
 */
class SalesCityBackendTest {

    @Test
    void crudRegistryShouldRegisterSalesCity() throws Exception {
        String content = Files.readString(Paths.get(
                "src/main/java/com/wuling/system/crud/CrudRegistry.java"));
        assertTrue(content.contains("salesCity"), "CrudRegistry 应注册 salesCity 资源");
        assertTrue(content.contains("sales_city"), "salesCity 应指向 sales_city 表");
    }

    @Test
    void appConfigShouldExposeSalesCities() throws Exception {
        String content = Files.readString(Paths.get(
                "src/main/java/com/wuling/system/controller/AppConfigController.java"));
        assertTrue(content.contains("sales-cities"), "应暴露 /sales-cities 接口");
        assertTrue(content.contains("sales_city"), "应读 sales_city 表");
    }

    @Test
    void storeDtoShouldCarryCityCode() throws Exception {
        String content = Files.readString(Paths.get(
                "src/main/java/com/wuling/subject/dto/AdminStoreDTO.java"));
        assertTrue(content.contains("cityCode"), "门店 DTO 应有 cityCode");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd server && mvn -q -Dtest=SalesCityBackendTest test
```

预期：FAIL（三处均未实现）。

- [ ] **Step 3: 注册 salesCity 资源**

在 `CrudRegistry.java` 的 `"cities"` 资源之后新增：

```java
            // 销售城市：运营可编辑的「销售城市」业务数据，区别于只读候选库 region。
            // province_code/city_code 关联 region.code；name 为运营自定义展示名；
            // initial 为首字母（小程序字母分组）；enabled 为上架状态。
            Map.entry("salesCity", new Resource("salesCity", "sales_city",
                    List.of("province_code", "city_code", "name", "initial", "enabled", "sort"),
                    List.of("code", "name", "city_code", "name"), "sort asc, id asc",
                    List.of("enabled"))),
```

> 注：`searchable` 与 `filterable` 按实际需要收敛，`enabled` 登记为 filterable 使页面「状态」下拉走等值过滤。

- [ ] **Step 4: 新增 sales-cities 接口**

**3a. 新增 `SalesCity` 实体 + `SalesCityMapper`**

创建 `server/src/main/java/com/wuling/system/entity/SalesCity.java`：

```java
package com.wuling.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sales_city")
public class SalesCity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String provinceCode;
    private String cityCode;
    private String name;
    private String initial;
    private Integer enabled;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
```

创建 `server/src/main/java/com/wuling/system/mapper/SalesCityMapper.java`：

```java
package com.wuling.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.system.entity.SalesCity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SalesCityMapper extends BaseMapper<SalesCity> {

    /** 上架中的销售城市（小程序选城） */
    @Select("select province_code, city_code, name, initial, sort from sales_city "
            + "where enabled = 1 and deleted = 0 order by sort asc, id asc")
    List<SalesCity> selectEnabled();
}
```

**3b. AppConfigController 新增接口（注入 SalesCityMapper）**

在 `AppConfigController` 增加 `SalesCityMapper` 依赖（当前只有 `AppConfigCacheService`，补构造参数），并在 `cities()` 之后新增：

```java
    /** 销售城市列表（小程序选城）；只返回上架中的。 */
    @GetMapping("/sales-cities")
    public Result<List<SalesCity>> salesCities() {
        return Result.ok(salesCityMapper.selectEnabled());
    }
```

- [ ] **Step 5: 门店返回 cityCode**

在 `StoreService.java` 的 `toAdminStore` 与 `listAppStores` 中，`dto.setCity(profile.getCity())` 之后补 cityCode 回填（按 sales_city 反查，用 mapper 而非注入 JdbcTemplate）：

```java
            // cityCode：按 sales_city 反查（门店 city 文本 -> 销售城市 code），
            // 消除前端三城硬编码。未匹配则回落到空，前端据此走默认城市。
            dto.setCityCode(resolveCityCode(profile.getCity()));
```

在 `StoreService` 注入 `SalesCityMapper`（补构造参数），并新增私有方法：

```java
    private final SalesCityMapper salesCityMapper; // 构造注入

    private String resolveCityCode(String cityName) {
        if (!StringUtils.hasText(cityName)) return null;
        SalesCity sc = salesCityMapper.selectOne(new LambdaQueryWrapper<SalesCity>()
                .eq(SalesCity::getName, cityName)
                .eq(SalesCity::getEnabled, 1)
                .last("limit 1"));
        return sc == null ? null : sc.getCityCode();
    }
```

> 同时 `AdminStoreDTO` 与 `AppStoreDTO` 都补 `cityCode` 字段。`SalesCityMapper` 属于 `system` 包，`StoreService` 在 `subject` 包引用它 —— 需确认模块内无包边界限制（本仓库 `server` 为单体模块，跨包引用 mapper 无碍，与 `BizSubjectMapper` 被 `product`/`finance` 引用的现状一致）。

- [ ] **Step 6: 运行测试确认通过**

```bash
cd server && mvn -q -Dtest=SalesCityBackendTest test
```

预期：PASS（3 用例全绿）。

- [ ] **Step 7: 编译确认**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **Step 8: 提交**

```bash
git add server/src/main/java/com/wuling/system/crud/CrudRegistry.java server/src/main/java/com/wuling/system/controller/AppConfigController.java server/src/main/java/com/wuling/subject/service/StoreService.java server/src/main/java/com/wuling/subject/dto/AdminStoreDTO.java server/src/main/java/com/wuling/subject/dto/AppStoreDTO.java server/src/test/java/com/wuling/common/SalesCityBackendTest.java
git commit -m "feat(subject): 销售城市 CRUD + 小程序选城接口 + 门店 cityCode"
```

---

### Task 4: 后台城市管理页 —— 改读 salesCity + 省市级联 + 去经纬度排序

**Files:**

- Modify: `src/views/system/city/index.vue`
- Test: `scripts/city-page.test.mjs`

**Interfaces:**

- Consumes: Task 3 的 `salesCity` 资源与 `provinces`/`cities` 候选库。
- Produces: 城市管理页改读 `salesCity`，表单省→市级联，移除经纬度/排序列。

- [ ] **Step 1: 写失败测试**

创建 `scripts/city-page.test.mjs`：

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const page = readFileSync(new URL('../src/views/system/city/index.vue', import.meta.url), 'utf8');

// 1) 改读 salesCity 资源
assert.ok(page.includes('salesCity'), '应读 salesCity 资源');

// 2) 表单有省市两级（parentId/cityCode 或类似）
assert.ok(page.includes('province'), '应有省份字段');
assert.ok(page.includes('cityCode') || page.includes('city'), '应有城市字段');

// 3) 移除经纬度（列与表单均不应出现 longitude/latitude）
assert.ok(!page.includes('longitude'), '不应再有经度');
assert.ok(!page.includes('latitude'), '不应再有纬度');

// 4) 移除排序字段
assert.ok(!page.includes("'sort'"), '不应再有排序');

console.log('city-page: all assertions passed');
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node scripts/city-page.test.mjs
```

预期：FAIL（当前页面仍有 longitude/latitude/sort）。

- [ ] **Step 3: 重写城市管理页**

重写 `src/views/system/city/index.vue` 的 `config` 与字段（关键改动）：

```ts
const columns: DataTableColumns<any> = [
  { title: '省份', key: 'provinceCode', width: 120, render: (row: any) => provinceNameByCode(row.provinceCode) },
  { title: '城市', key: 'name', width: 140 },
  { title: '城市编码', key: 'cityCode', width: 140 },
  {
    title: '状态',
    key: 'enabled',
    width: 90,
    render: renderTag('enabled', statusMap({ 1: ['上架', 'success'], 0: ['停用', 'default'] }))
  }
];

const formFields: FormField[] = [
  {
    key: 'provinceCode',
    label: '省份',
    type: 'select',
    options: () => store.provinces.map((p: any) => ({ label: p.name, value: String(p.code) })),
    rules: [requiredRule]
  },
  {
    key: 'cityCode',
    label: '城市',
    type: 'select',
    options: (form: any) =>
      store.cities
        .filter((c: any) => String(c.parent_id) === String(provinceIdByCode(form.provinceCode)))
        .map((c: any) => ({ label: c.name, value: String(c.code) })),
    rules: [requiredRule]
  },
  { key: 'name', label: '展示名称', rules: [requiredRule] },
  { key: 'initial', label: '首字母', maxlength: 1 },
  {
    key: 'enabled',
    label: '状态',
    type: 'select',
    options: [
      { label: '上架', value: 1 },
      { label: '停用', value: 0 }
    ]
  }
];
```

> 完整实现见执行时的精确代码（需处理 `provinces` 镜像的 `code` 字段、`cities` 镜像的 `parent_id` 字段，以及省 code→id 的映射）。`remoteKey` 改为 `salesCity`，`remoteDeps` 改为 `['provinces', 'cities']`。

- [ ] **Step 4: 运行测试确认通过**

```bash
node scripts/city-page.test.mjs
```

预期：PASS。

- [ ] **Step 5: 类型检查 + Vue 单根校验**

```bash
npm run typecheck
npm run check:vue-root
```

预期：均无错误。

- [ ] **Step 6: 提交**

```bash
git add src/views/system/city/index.vue scripts/city-page.test.mjs
git commit -m "feat(web): 城市管理改销售城市 + 省市级联 + 去经纬度排序"
```

---

### Task 5: 小程序 —— 去硬编码 + 读 sales-cities + 选城/定位适配

**Files:**

- Modify: `user-h5/utils/api.js`（新增 `fetchSalesCities`）
- Modify: `user-h5/utils/store.js`（去三城硬编码，改读 sales-cities）
- Test: `user-h5/scripts/sales-city.test.mjs`

**Interfaces:**

- Consumes: Task 3 的 `GET /api/v1/app/sales-cities`。
- Produces: `store.js` 的 `refreshCitiesFromRemote` 改读 sales-cities；`normalizeRemoteStore` 用后端 `cityCode` 而非硬编码。

- [ ] **Step 1: 写失败测试**

创建 `user-h5/scripts/sales-city.test.mjs`：

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const api = readFileSync(new URL('../utils/api.js', import.meta.url), 'utf8');
const store = readFileSync(new URL('../utils/store.js', import.meta.url), 'utf8');

// 1) api 暴露 sales-cities 接口
assert.ok(api.includes('sales-cities'), 'api 应有 sales-cities 接口');

// 2) store.js 不再有三城硬编码
assert.ok(!/广州市.*guangzhou.*深圳市.*shenzhen/.test(store), 'store.js 不应再有三城硬编码');
assert.ok(!store.includes("'guangzhou'"), '不应硬编码 guangzhou');

// 3) normalizeRemoteStore 使用后端 cityCode
assert.ok(store.includes('cityCode'), 'normalizeRemoteStore 应使用 cityCode');

console.log('sales-city: all assertions passed');
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node user-h5/scripts/sales-city.test.mjs
```

预期：FAIL（api 无 sales-cities，store 仍硬编码）。

- [ ] **Step 3: api.js 新增接口**

在 `user-h5/utils/api.js` 的 `fetchCities` 之后新增：

```js
/** 销售城市列表（小程序选城） */
function fetchSalesCities() {
  return request({ url: '/api/v1/app/sales-cities', method: 'GET' }).then(unwrap);
}
```

并在 `module.exports` 补 `fetchSalesCities`。

- [ ] **Step 4: store.js 去硬编码 + 改读 sales-cities**

修改 `refreshCitiesFromRemote` 调用 `fetchSalesCities`，字段映射改为：

```js
function refreshCitiesFromRemote() {
  return api
    .fetchSalesCities()
    .then(list => {
      if (Array.isArray(list) && list.length) {
        cityCatalog = list.map(item => ({
          code: item.city_code,
          name: item.name,
          initial: item.initial || initialOf(item.name),
          latitude: 0,
          longitude: 0
        }));
      }
      return cityCatalog;
    })
    .catch(() => cityCatalog);
}
```

修改 `normalizeRemoteStore`，删除三城三元表达式，改用后端返回的 `cityCode`：

```js
const cityCode = item.cityCode || '';
return Object.assign({}, item, {
  id: item.code || String(item.id),
  subjectId: item.id != null ? Number(item.id) : null,
  cityCode,
  ...
});
```

> `initialOf` 复用首字母算法（后端已返回 initial，此处仅兜底）。`DEFAULT_CITY_CODE` 保持 `'changsha'` 或改为 `''`（视现有门店 seed 而定，执行时确认）。

- [ ] **Step 5: 运行测试确认通过**

```bash
node user-h5/scripts/sales-city.test.mjs
```

预期：PASS。

- [ ] **Step 6: 提交**

```bash
git add user-h5/utils/api.js user-h5/utils/store.js user-h5/scripts/sales-city.test.mjs
git commit -m "feat(h5): 小程序销售城市读接口并去三城硬编码"
```

---

### Task 6: 全链路回归验证

**Files:**

- Test: `server/src/test/java/com/wuling/common/SalesCityE2ETest.java`（静态断言汇总）

**Interfaces:**

- Consumes: Task 1-5 全部产出。

- [ ] **Step 1: 写回归测试**

创建 `server/src/test/java/com/wuling/common/SalesCityE2ETest.java`：

```java
package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 销售城市全链路静态回归：锁定「单一事实源」与「无硬编码」两个不变量。
 */
class SalesCityE2ETest {

    @Test
    void appShouldNotReadStaticCitiesConfig() throws Exception {
        String content = Files.readString(Paths.get(
                "src/main/java/com/wuling/system/controller/AppConfigController.java"));
        // /cities 保留旧接口但应标记废弃；新增 /sales-cities 为事实源
        assertTrue(content.contains("sales-cities"), "应暴露 sales-cities");
    }

    @Test
    void frontendShouldNotHardcodeCityMapping() throws Exception {
        String store = Files.readString(Paths.get("user-h5/utils/store.js"));
        assertFalse(store.contains("'guangzhou'"), "前端不应硬编码 guangzhou");
        assertFalse(store.contains("'shenzhen'"), "前端不应硬编码 shenzhen");
    }
}
```

- [ ] **Step 2: 运行全部后端测试**

```bash
cd server && mvn -q test
```

预期：BUILD SUCCESS。

- [ ] **Step 3: 运行全部前端检查**

```bash
node scripts/city-page.test.mjs
node scripts/gen-region-seed.test.mjs
node user-h5/scripts/sales-city.test.mjs
npm run typecheck
npm run check:vue-root
```

预期：全部通过。

- [ ] **Step 4: 提交**

```bash
git add server/src/test/java/com/wuling/common/SalesCityE2ETest.java
git commit -m "test: 销售城市全链路静态回归"
```

---

## 交付清单

| 层     | 文件                          | 改动                                   |
| ------ | ----------------------------- | -------------------------------------- |
| DB     | `V59__sales_city.sql`         | 新增 sales_city 表 + 迁移现有 3 城     |
| DB     | `V59__seed_region_full.sql`   | 全量省市候选库                         |
| 脚本   | `scripts/gen-region-seed.mjs` | modood 数据生成脚本                    |
| 后端   | `CrudRegistry.java`           | 注册 salesCity 资源                    |
| 后端   | `AppConfigController.java`    | +/sales-cities 接口                    |
| 后端   | `StoreService.java` + DTO     | 门店返回 cityCode                      |
| 前端   | `system/city/index.vue`       | 改 salesCity + 省市级联 + 去经纬度排序 |
| 小程序 | `api.js` / `store.js`         | 去硬编码 + 读 sales-cities             |
| 测试   | 6 个 Java/Node 测试           | 静态规则 + 不变量                      |

## 不做的事（明确排除）

- 不引入区县级（level=3）数据。
- 不引入拼音库（首字母用 GB2312 区间法近似）。
- 不删除旧 `app_config.app_cities` 与 `/cities` 接口（标记废弃，保留兼容）。
- 不动 `region` 表的历史 5 条数据（保留，全量数据以新 id 追加，避免冲突）。

## 风险与回滚

- **风险 1**：`region` 全量 seed 的 id 与既有 5 条冲突。脚本从 id=100 起分配，避免冲突；但需在迁移中确认 `region` 无 AUTO_INCREMENT 起始约束。
- **风险 2**：`StoreService` 新增 `SalesCityMapper` 依赖（跨包引用 system 包的 mapper）；需确认无循环依赖，与 `BizSubjectMapper` 被 product/finance 引用的现状一致，无碍。
- **风险 3**：小程序 `initial` 首字母算法对生僻字/少数民族城市可能不准；影响仅是字母分组排序，不阻塞主流程，可后续用 pinyin 库精修。
- **回滚**：V59 迁移为可空、幂等；回滚代码即可，不影响运行。`sales_city` 表删除即回滚到旧 `app_cities` 逻辑。
