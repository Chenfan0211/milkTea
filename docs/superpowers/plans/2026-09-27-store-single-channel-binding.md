# 门店 ↔ 资源方 唯一绑定与分佣归因统一实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 门店列表显示所属资源方；一个门店只能绑定一个资源方（资源方可绑多门店）；资源方「绑定门店」下拉过滤已占用门店；并把分佣归因统一到 `store_profile.channel_subject_id` 单一事实源，消除算错钱风险。

**Architecture:** 在 `store_profile` 增加 `channel_subject_id` 单列表达「一店一资源方」；后端所有渠道归因读路径（下单归因、分账、工作台、资源方列表统计）统一切到该列；`channel_store` 降级为过渡期兼容镜像，绑定/解绑同事务双写。前端门店页新增资源方列与绑定/解绑操作，资源方弹窗下拉按「门店未绑定资源方」过滤。

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / JdbcTemplate / Flyway / MySQL；Vue 3 + TypeScript + Naive UI (soybean-admin)；JUnit 5。

**Spec:** 用户需求（本会话）：门店管理显示资源方信息；一门店仅一资源方；资源方绑定门店下拉过滤已绑定门店；分佣功能需同步排查调整。已确认两项决策：① 采用方案 A（`store_profile` 加列）；② 分佣归因统一以 `store_profile.channel_subject_id` 为准，`channel_store` 降级为兼容镜像；历史 `split_snapshot` 不回算。

## Global Constraints

- 分佣归因唯一事实源 = `store_profile.channel_subject_id`；`channel_store` 仅作过渡镜像，不得作为资金归属依据。
- 绑定/解绑必须在**同一事务**内写 `store_profile.channel_subject_id` 与 `channel_store`。
- 一门店只能绑定一个资源方；一资源方可绑定多个门店。重复绑定同店同资源方视为幂等成功，绑到已被其他资源方占用的门店必须报错。
- 历史 `split_snapshot` 不回算；V58 之前的历史订单保留原归因。
- 分账守恒不变：`平台 + 门店 + 资源方 + 投资人 + 供应商 == 实付`。
- 迁移文件命名 `V58__store_single_channel.sql`，幂等、可重复执行（`INSERT ... SELECT + NOT EXISTS` 风格）。
- 前端颜色 / 字号 / 圆角只取项目既有 token；沿用 `src/views/subject/*` 现有写法，不引入新组件库。
- 每个任务结束独立可验证；每任务一次提交。

---

## 现状与风险基线

- `store_profile` 无渠道列，仅有 `investor_subject_id`（V1 schema 第 117-141 行）。
- 渠道归因当前单一读路径 `OrderMapper.selectBoundChannel`（`trade-service`）读 `channel_store`。
- 资源方门店数与门店 ID 列表在 `AdminSubjectProfileController.toRow`（CHANNEL 分支）读 `channel_store`。
- `SubjectQueryPort.findStoreIdsByChannel` → `BizSubjectMapper.selectStoreIdsByChannel` 读 `channel_store`。
- 若不改读路径直接加列，会出现三类线上故障：改绑后仍归因旧资源方（R1）、门店页绑定未同步导致资源方拿不到钱（R2）、下拉过滤与资金归因数据源不一致（R3）。

---

## 任务总览（严格按此顺序执行，避免中间态算错钱）

1. Task 1：V58 迁移（加列 + 回填 + 索引）
2. Task 2：后端读路径统一切到 `store_profile.channel_subject_id`（消除 R1/R2/R3）
3. Task 3：绑定/解绑双写 + 占用校验 + 可绑门店接口
4. Task 4：门店列表 / 资源方列表暴露资源方信息
5. Task 5：前端门店页资源方列与绑定/解绑
6. Task 6：前端资源方弹窗下拉过滤已占用门店
7. Task 7：全链路回归验证

---

### Task 1: V58 迁移 —— store_profile 增加唯一资源方列

**Files:**

- Create: `server/src/main/resources/db/migration/V58__store_single_channel.sql`
- Test: `server/src/test/java/com/wuling/common/StoreSingleChannelMigrationV58Test.java`

**Interfaces:**

- Consumes: 现有表 `store_profile(id, subject_id, ...)`、`channel_store(id, channel_subject_id, store_subject_id, deleted)`。
- Produces: 列 `store_profile.channel_subject_id BIGINT UNSIGNED NULL`；索引 `idx_store_profile_channel (channel_subject_id)`。

- [ ] **Step 1: 写迁移文件**

创建 `server/src/main/resources/db/migration/V58__store_single_channel.sql`：

```sql
-- =====================================================================
-- V58：门店唯一资源方（一店一资源方）
--
-- 背景：业务要求「一个门店只能绑定一个资源方，一个资源方可以绑定多个门店」。
--   · 旧模型 channel_store 是多对多，无法表达该约束；
--   · 分佣归因 OrderMapper.selectBoundChannel 读 channel_store order by id limit 1，
--     门店改绑后旧行仍在 deleted=0，会把分佣算给旧资源方。
--
-- 方案（已与需求方确认）：
--   · store_profile 增加 channel_subject_id 作为唯一事实源；
--   · channel_store 降级为过渡期兼容镜像，绑定/解绑由应用层同事务双写；
--   · 历史 split_snapshot 不回算。
--
-- 幂等：加列/加索引前先判存在，可重复执行。
-- =====================================================================

-- 1) 新增列（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，用存储过程判存在）
DROP PROCEDURE IF EXISTS v58_add_channel_subject_id;
DELIMITER $$
CREATE PROCEDURE v58_add_channel_subject_id()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'store_profile'
          AND COLUMN_NAME = 'channel_subject_id'
    ) THEN
        ALTER TABLE store_profile
            ADD COLUMN channel_subject_id BIGINT UNSIGNED NULL
            COMMENT '门店唯一资源方(渠道)主体ID';
    END IF;
END$$
DELIMITER ;
CALL v58_add_channel_subject_id();
DROP PROCEDURE IF EXISTS v58_add_channel_subject_id;

-- 2) 回填：从 channel_store 取每个门店当前有效资源方。
--    存在多条有效行时（历史脏数据）取 id 最小的一条，保证确定性。
UPDATE store_profile sp
SET sp.channel_subject_id = (
    SELECT cs.channel_subject_id
    FROM channel_store cs
    WHERE cs.store_subject_id = sp.subject_id
      AND cs.deleted = 0
    ORDER BY cs.id
    LIMIT 1
)
WHERE sp.deleted = 0
  AND sp.channel_subject_id IS NULL
  AND EXISTS (
      SELECT 1 FROM channel_store cs
      WHERE cs.store_subject_id = sp.subject_id AND cs.deleted = 0
  );

-- 3) 查询索引：按资源方统计门店是高频读路径。
--    不用 UNIQUE(subject_id, channel_subject_id)：store_profile 已有
--    uk_store_profile_subject(subject_id) 唯一键，单店本就只有一行，
--    「一店一资源方」由该行单列天然保证，复合唯一键属冗余。
DROP PROCEDURE IF EXISTS v58_add_channel_index;
DELIMITER $$
CREATE PROCEDURE v58_add_channel_index()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'store_profile'
          AND INDEX_NAME = 'idx_store_profile_channel'
    ) THEN
        ALTER TABLE store_profile
            ADD KEY idx_store_profile_channel (channel_subject_id);
    END IF;
END$$
DELIMITER ;
CALL v58_add_channel_index();
DROP PROCEDURE IF EXISTS v58_add_channel_index;
```

> **注意**：`DELIMITER $$` + `CREATE PROCEDURE` 的幂等写法与本项目既有迁移一致（见 `V12__concurrency_safety.sql:26-39`，Flyway 原生支持 `DELIMITER` 指令），照抄即可，无需改动 Flyway 配置。

- [ ] **Step 2: 写失败测试**

创建 `server/src/test/java/com/wuling/common/StoreSingleChannelMigrationV58Test.java`：

```java
package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V58 迁移静态校验：确保门店唯一资源方列与回填逻辑存在。
 *
 * <p>只做文本断言（不需要数据库），与 MigrationFilesTest 同风格。
 */
class StoreSingleChannelMigrationV58Test {

    private final Path file = Paths.get("src/main/resources/db/migration/V58__store_single_channel.sql");

    private String sql() throws Exception {
        return Files.readString(file);
    }

    @Test
    void migrationFileShouldExist() {
        assertTrue(Files.exists(file), "V58 迁移文件应存在");
    }

    @Test
    void shouldAddChannelSubjectIdColumn() throws Exception {
        String content = sql();
        assertTrue(content.contains("channel_subject_id"), "应包含 channel_subject_id 列定义");
        assertTrue(content.contains("ALTER TABLE store_profile"), "应对 store_profile 加列");
    }

    @Test
    void shouldBackfillFromChannelStore() throws Exception {
        String content = sql();
        assertTrue(content.contains("UPDATE store_profile sp"), "应包含回填语句");
        assertTrue(content.contains("FROM channel_store"), "回填数据源应为 channel_store");
    }

    @Test
    void shouldBeIdempotent() throws Exception {
        String content = sql();
        assertTrue(content.contains("information_schema.COLUMNS"), "加列需判存在以保证幂等");
        assertTrue(content.contains("information_schema.STATISTICS"), "加索引需判存在以保证幂等");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd server && mvn -q -Dtest=StoreSingleChannelMigrationV58Test test
```

预期：FAIL —— `V58 迁移文件应存在` 失败（文件尚未创建；若先建了文件则应为 PASS，据此确认测试确实在执行）。

- [ ] **Step 4: 补齐迁移文件后让测试通过**

确认 Step 1 文件已落盘，重新运行：

```bash
cd server && mvn -q -Dtest=StoreSingleChannelMigrationV58Test test
```

预期：PASS（4 个用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add server/src/main/resources/db/migration/V58__store_single_channel.sql server/src/test/java/com/wuling/common/StoreSingleChannelMigrationV58Test.java
git commit -m "feat(db): V58 门店唯一资源方列与回填"
```

---

### Task 2: 分佣归因读路径统一切到 store_profile

**Files:**

- Modify: `trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java:12-14`
- Modify: `server/src/main/java/com/wuling/subject/mapper/BizSubjectMapper.java:16-18`
- Modify: `server/src/main/java/com/wuling/subject/port/SubjectQueryPort.java`
- Modify: `server/src/main/java/com/wuling/subject/port/LocalSubjectQueryAdapter.java`
- Modify: `server/src/main/java/com/wuling/subject/controller/AdminSubjectProfileController.java`
- Test: `server/src/test/java/com/wuling/common/ChannelAttributionSourceTest.java`

**Interfaces:**

- Consumes: Task 1 的 `store_profile.channel_subject_id`。
- Produces: `SubjectQueryPort.findChannelOfStore(Long storeSubjectId): Long`；`BizSubjectMapper.selectChannelOfStore(Long)`；`BizSubjectMapper.selectStoreIdsByChannel` 语义不变但改读 `store_profile`。

- [ ] **Step 1: 写失败测试**

创建 `server/src/test/java/com/wuling/common/ChannelAttributionSourceTest.java`：

```java
package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道归因单一事实源静态校验。
 *
 * <p>背景：分佣必须与门店列表、下拉过滤读同一字段，
 * 否则出现「改绑后仍归因旧资源方」「门店页绑定后资源方拿不到钱」。
 * 本测试锁定关键读路径 SQL，防止后续被改回 channel_store。
 */
class ChannelAttributionSourceTest {

    private String read(String relative) throws Exception {
        return Files.readString(Paths.get(relative));
    }

    @Test
    void orderAttributionShouldReadStoreProfile() throws Exception {
        String mapper = read("trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java");
        assertTrue(mapper.contains("from store_profile"), "下单归因 selectBoundChannel 必须读 store_profile");
        assertFalse(mapper.contains("from channel_store"), "下单归因不得再读 channel_store");
    }

    @Test
    void storeIdsByChannelShouldReadStoreProfile() throws Exception {
        String mapper = read("server/src/main/java/com/wuling/subject/mapper/BizSubjectMapper.java");
        assertTrue(mapper.contains("from store_profile where channel_subject_id"),
                "按渠道取门店列表必须读 store_profile.channel_subject_id");
    }

    @Test
    void subjectQueryPortShouldExposeFindChannelOfStore() throws Exception {
        String port = read("server/src/main/java/com/wuling/subject/port/SubjectQueryPort.java");
        assertTrue(port.contains("findChannelOfStore"), "SubjectQueryPort 应暴露 findChannelOfStore");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd server && mvn -q -Dtest=ChannelAttributionSourceTest test
```

预期：FAIL —— `orderAttributionShouldReadStoreProfile` 断言 `from store_profile` 失败（当前是 `channel_store`）。

- [ ] **Step 3: 改 OrderMapper 归因 SQL（核心）**

把 `trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java` 整体替换为：

```java
package com.wuling.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.trade.entity.Order;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 门店绑定的渠道（唯一），用于渠道永久归因。
     *
     * <p>读 store_profile.channel_subject_id —— 与门店列表、绑定校验同一事实源。
     * 历史实现读 channel_store（多对多）会导致改绑后旧行仍生效，把分佣算给旧资源方。
     */
    @Select("select channel_subject_id from store_profile "
            + "where subject_id = #{storeSubjectId} and deleted = 0 limit 1")
    Long selectBoundChannel(@Param("storeSubjectId") Long storeSubjectId);
}
```

- [ ] **Step 4: 改 BizSubjectMapper（两处）**

把 `server/src/main/java/com/wuling/subject/mapper/BizSubjectMapper.java` 中的 `selectStoreIdsByChannel` 改为读 `store_profile`，并新增 `selectChannelOfStore`：

```java
    /** 渠道绑定的门店主体 ID 列表（读门店侧唯一归属列，与分佣归因同源） */
    @Select("select subject_id from store_profile "
            + "where channel_subject_id = #{channelSubjectId} and deleted = 0")
    List<Long> selectStoreIdsByChannel(@Param("channelSubjectId") Long channelSubjectId);

    /** 门店绑定的资源方（渠道）主体 ID（本期单店单资源方） */
    @Select("select channel_subject_id from store_profile "
            + "where subject_id = #{storeSubjectId} and deleted = 0 limit 1")
    Long selectChannelOfStore(@Param("storeSubjectId") Long storeSubjectId);
```

- [ ] **Step 5: SubjectQueryPort 增加 findChannelOfStore**

在 `server/src/main/java/com/wuling/subject/port/SubjectQueryPort.java` 的 `findInvestorOfStore` 之后插入：

```java
    /**
     * 门店绑定的资源方（渠道）主体 ID。
     *
     * <p>用于分账时确定资源方份额归属，与 {@link #findInvestorOfStore} 对称。
     *
     * @return 资源方主体 ID；未绑定返回 null
     */
    Long findChannelOfStore(Long storeSubjectId);
```

在 `server/src/main/java/com/wuling/subject/port/LocalSubjectQueryAdapter.java` 的 `findInvestorOfStore` 之后插入实现：

```java
    @Override
    public Long findChannelOfStore(Long storeSubjectId) {
        if (storeSubjectId == null) {
            return null;
        }
        return bizSubjectMapper.selectChannelOfStore(storeSubjectId);
    }
```

- [ ] **Step 6: 改资源方列表统计读源**

修改 `server/src/main/java/com/wuling/subject/controller/AdminSubjectProfileController.java` 的 CHANNEL 分支：

```java
        if ("CHANNEL".equals(type)) {
            Map<String, Object> p = profileOne("channel_profile", id);
            row.put("location", p == null ? null : p.get("location"));
            row.put("storeType", p == null ? null : p.get("store_type"));
            // 读门店侧唯一归属列，保证「绑定门店数」与实际分佣归因一致
            Long boundStoreCount = jdbcTemplate.queryForObject(
                    "select count(*) from store_profile where channel_subject_id = ? and deleted = 0",
                    Long.class, id);
            row.put("boundStoreCount", boundStoreCount == null ? 0 : boundStoreCount.intValue());
            List<Map<String, Object>> channelStores = jdbcTemplate.queryForList(
                    "select subject_id from store_profile where channel_subject_id = ? and deleted = 0", id);
            List<Long> channelStoreIds = new ArrayList<>();
            for (Map<String, Object> cs : channelStores) {
                channelStoreIds.add(((Number) cs.get("subject_id")).longValue());
            }
            row.put("boundStoreIds", channelStoreIds);
        } else if ("SUPPLIER".equals(type)) {
```

> **注意**：原 `channel_store` 查询列名是 `store_subject_id`，改读 `store_profile` 后列名变为 `subject_id`，循环内取值键必须同步改，否则抛 `NullPointerException`。

- [ ] **Step 7: 运行测试确认通过**

```bash
cd server && mvn -q -Dtest=ChannelAttributionSourceTest test
```

预期：PASS（3 个用例全绿）。

- [ ] **Step 8: 全量编译确认无破坏**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS（`SubjectQueryPort` 新方法与实现、`BizSubjectMapper` 新方法均编译通过）。

- [ ] **Step 9: 提交**

```bash
git add trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java server/src/main/java/com/wuling/subject/mapper/BizSubjectMapper.java server/src/main/java/com/wuling/subject/port/SubjectQueryPort.java server/src/main/java/com/wuling/subject/port/LocalSubjectQueryAdapter.java server/src/main/java/com/wuling/subject/controller/AdminSubjectProfileController.java server/src/test/java/com/wuling/common/ChannelAttributionSourceTest.java
git commit -m "fix(finance): 渠道归因统一读 store_profile.channel_subject_id"
```

---

### Task 3: 绑定/解绑双写 + 占用校验 + 可绑门店接口

**Files:**

- Modify: `server/src/main/java/com/wuling/subject/entity/StoreProfile.java`
- Modify: `server/src/main/java/com/wuling/subject/controller/SubjectBindingController.java`
- Test: `server/src/test/java/com/wuling/subject/StoreChannelBindingRuleTest.java`

**Interfaces:**

- Consumes: Task 1 列、Task 2 读路径。
- Produces: `POST /api/v1/admin/subject/binding/store/{storeSubjectId}/channel/{channelSubjectId}`、`DELETE /api/v1/admin/subject/binding/store/{storeSubjectId}/channel`、`GET /api/v1/admin/subject/binding/channel/{channelSubjectId}/bindable-stores`；`StoreProfile.channelSubjectId`。

- [ ] **Step 1: StoreProfile 增加字段**

在 `server/src/main/java/com/wuling/subject/entity/StoreProfile.java` 的 `investorSubjectId` 之后插入：

```java
    /** 门店唯一资源方（渠道）主体 ID：一店一资源方，一资源方可绑多门店 */
    private Long channelSubjectId;
```

- [ ] **Step 2: 写失败测试**

创建 `server/src/test/java/com/wuling/subject/StoreChannelBindingRuleTest.java`：

```java
package com.wuling.subject;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门店↔资源方 唯一绑定规则静态校验。
 *
 * <p>锁定四个必备能力：门店侧绑定入口、占用校验、可绑门店过滤接口、双写。
 * 数据库行为由 Task 7 的人工/集成回归覆盖。
 */
class StoreChannelBindingRuleTest {

    private String controller() throws Exception {
        return Files.readString(Paths.get(
                "src/main/java/com/wuling/subject/controller/SubjectBindingController.java"));
    }

    @Test
    void storeProfileShouldCarryChannelSubjectId() throws Exception {
        String entity = Files.readString(Paths.get(
                "src/main/java/com/wuling/subject/entity/StoreProfile.java"));
        assertTrue(entity.contains("channelSubjectId"), "StoreProfile 应有 channelSubjectId 字段");
    }

    @Test
    void bindingControllerShouldExposeStoreSideBindAndUnbind() throws Exception {
        String content = controller();
        assertTrue(content.contains("/store/{storeSubjectId}/channel/{channelSubjectId}"),
                "应有门店侧绑定资源方入口");
        assertTrue(content.contains("unbindStoreChannel"), "应有门店侧解绑资源方方法");
    }

    @Test
    void bindShouldRejectStoreOccupiedByOtherChannel() throws Exception {
        String content = controller();
        assertTrue(content.contains("已绑定其他资源方"), "绑定时应拒绝已被其他资源方占用的门店");
    }

    @Test
    void shouldExposeBindableStoresEndpoint() throws Exception {
        String content = controller();
        assertTrue(content.contains("bindable-stores"), "应提供可绑门店查询接口");
    }

    @Test
    void bindShouldWriteBothTables() throws Exception {
        String content = controller();
        assertTrue(content.contains("setChannelSubjectId"), "绑定应写入 store_profile.channel_subject_id");
        assertTrue(content.contains("channel_store"), "过渡期应同事务维护 channel_store 镜像");
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd server && mvn -q -Dtest=StoreChannelBindingRuleTest test
```

预期：FAIL —— `bindingControllerShouldExposeStoreSideBindAndUnbind` 等多个用例失败。

- [ ] **Step 4: 实现绑定/解绑/可绑门店接口**

4a. 在 `SubjectBindingController` 中，把现有 `bindChannelStore` 方法整体替换为（保留 URL 与语义，增强校验 + 双写）：

```java
    @PostMapping("/channel/{channelSubjectId}/store/{storeSubjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> bindChannelStore(@PathVariable Long channelSubjectId, @PathVariable Long storeSubjectId) {
        requireSubject(channelSubjectId, "CHANNEL");
        requireSubject(storeSubjectId, "STORE");

        // 一店一资源方：门店已被其他资源方占用则拒绝
        Long occupiedByOther = jdbcTemplate.queryForObject(
                "select count(*) from store_profile where subject_id = ? and deleted = 0 "
                        + "and channel_subject_id is not null and channel_subject_id <> ?",
                Long.class, storeSubjectId, channelSubjectId);
        if (occupiedByOther != null && occupiedByOther > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该门店已绑定其他资源方，请先解绑");
        }

        // 幂等：已绑定同一资源方直接返回成功
        Long alreadyBound = jdbcTemplate.queryForObject(
                "select count(*) from store_profile where subject_id = ? and deleted = 0 "
                        + "and channel_subject_id = ?",
                Long.class, storeSubjectId, channelSubjectId);
        if (alreadyBound != null && alreadyBound > 0) {
            return Result.ok();
        }

        // 写唯一事实源：store_profile.channel_subject_id
        jdbcTemplate.update("update store_profile set channel_subject_id = ?, update_time = now() "
                        + "where subject_id = ? and deleted = 0",
                channelSubjectId, storeSubjectId);
        // 过渡期镜像
        mirrorChannelStore(channelSubjectId, storeSubjectId);
        return Result.ok();
    }

    /**
     * 维护 channel_store 兼容镜像（过渡期）。
     *
     * <p>uk_channel_store(channel_subject_id, store_subject_id) 不含 deleted，
     * 解绑为逻辑删除后历史行仍在，直接 insert 会撞唯一键报 500。
     */
    private void mirrorChannelStore(Long channelSubjectId, Long storeSubjectId) {
        int revived = jdbcTemplate.update("update channel_store set deleted = 0, create_time = now() "
                        + "where channel_subject_id = ? and store_subject_id = ? and deleted = 1",
                channelSubjectId, storeSubjectId);
        if (revived == 0) {
            Long exists = jdbcTemplate.queryForObject(
                    "select count(*) from channel_store where channel_subject_id = ? and store_subject_id = ?",
                    Long.class, channelSubjectId, storeSubjectId);
            if (exists == null || exists == 0) {
                jdbcTemplate.update("insert into channel_store (channel_subject_id, store_subject_id) "
                        + "values (?, ?)", channelSubjectId, storeSubjectId);
            }
        }
    }
```

4b. 把现有 `unbindChannelStore`（资源方页单店解绑）替换为：

```java
    @DeleteMapping("/channel/{channelSubjectId}/store/{storeSubjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindChannelStore(@PathVariable Long channelSubjectId, @PathVariable Long storeSubjectId) {
        jdbcTemplate.update("update channel_store set deleted = 1 "
                        + "where channel_subject_id = ? and store_subject_id = ? and deleted = 0",
                channelSubjectId, storeSubjectId);
        // 仅当该门店当前归属正是本渠道时清空，避免误清其他渠道的归属
        jdbcTemplate.update("update store_profile set channel_subject_id = null, update_time = now() "
                        + "where subject_id = ? and channel_subject_id = ? and deleted = 0",
                storeSubjectId, channelSubjectId);
        return Result.ok();
    }
```

4c. 在 `unbindChannelStores`（批量解绑）的 `for` 循环体内补 `store_profile` 清理：

```java
        for (Long storeId : storeIds) {
            total += jdbcTemplate.update("update channel_store set deleted = 1 "
                            + "where channel_subject_id = ? and store_subject_id = ? and deleted = 0",
                    channelSubjectId, storeId);
            jdbcTemplate.update("update store_profile set channel_subject_id = null, update_time = now() "
                            + "where subject_id = ? and channel_subject_id = ? and deleted = 0",
                    storeId, channelSubjectId);
        }
```

4d. 在 `unbindChannelStore` 之后插入门店侧绑定/解绑与可绑门店接口：

```java
    // ---------- 门店 ↔ 资源方（一店一资源方） ----------

    /**
     * 门店侧绑定资源方。
     *
     * <p>与 {@link #bindChannelStore} 等价，两个入口对应两块 UI（门店管理页 / 资源方管理页）。
     */
    @PostMapping("/store/{storeSubjectId}/channel/{channelSubjectId}")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> bindStoreChannel(@PathVariable Long storeSubjectId, @PathVariable Long channelSubjectId) {
        return bindChannelStore(channelSubjectId, storeSubjectId);
    }

    /** 门店侧解绑资源方：清空唯一归属列 + 逻辑删除镜像行 */
    @DeleteMapping("/store/{storeSubjectId}/channel")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> unbindStoreChannel(@PathVariable Long storeSubjectId) {
        requireSubject(storeSubjectId, "STORE");
        jdbcTemplate.update("update store_profile set channel_subject_id = null, update_time = now() "
                        + "where subject_id = ? and deleted = 0",
                storeSubjectId);
        jdbcTemplate.update("update channel_store set deleted = 1 where store_subject_id = ? and deleted = 0",
                storeSubjectId);
        return Result.ok();
    }

    /**
     * 查询某资源方可绑定的门店（供下拉过滤）。
     *
     * <p>返回「尚未绑定任何资源方」的门店，以及「已绑定当前资源方」的门店，
     * 避免前端拉全量门店后自行判断造成数据源不一致。
     */
    @GetMapping("/channel/{channelSubjectId}/bindable-stores")
    public Result<List<Map<String, Object>>> bindableStores(@PathVariable Long channelSubjectId) {
        requireSubject(channelSubjectId, "CHANNEL");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select s.id, s.code, s.name, sp.city, sp.address "
                        + "from biz_subject s "
                        + "join store_profile sp on sp.subject_id = s.id and sp.deleted = 0 "
                        + "where s.deleted = 0 and s.subject_type = 'STORE' "
                        + "and (sp.channel_subject_id is null or sp.channel_subject_id = ?) "
                        + "order by s.id",
                channelSubjectId);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("code", r.get("code"));
            m.put("name", r.get("name"));
            m.put("city", r.get("city"));
            m.put("address", r.get("address"));
            result.add(m);
        }
        return Result.ok(result);
    }
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd server && mvn -q -Dtest=StoreChannelBindingRuleTest test
```

预期：PASS（5 个用例全绿）。

- [ ] **Step 6: 编译确认无破坏**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/wuling/subject/entity/StoreProfile.java server/src/main/java/com/wuling/subject/controller/SubjectBindingController.java server/src/test/java/com/wuling/subject/StoreChannelBindingRuleTest.java
git commit -m "feat(subject): 门店资源方唯一绑定与双写落地"
```

---

### Task 4: 门店列表 / 资源方列表暴露资源方信息

**Files:**

- Modify: `server/src/main/java/com/wuling/subject/dto/AdminStoreDTO.java`
- Modify: `server/src/main/java/com/wuling/subject/service/StoreService.java`（`toAdminStore`）
- Test: `server/src/test/java/com/wuling/subject/AdminStoreChannelFieldTest.java`

**Interfaces:**

- Consumes: Task 1 列、Task 3 `StoreProfile.channelSubjectId`。
- Produces: `AdminStoreDTO.channelSubjectId: Long`、`AdminStoreDTO.channelName: String`（未绑定为 `"未绑定"`）。

- [ ] **Step 1: 写失败测试**

创建 `server/src/test/java/com/wuling/subject/AdminStoreChannelFieldTest.java`：

```java
package com.wuling.subject;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门店列表资源方字段静态校验。
 */
class AdminStoreChannelFieldTest {

    @Test
    void dtoShouldCarryChannelFields() throws Exception {
        String dto = Files.readString(Paths.get(
                "src/main/java/com/wuling/subject/dto/AdminStoreDTO.java"));
        assertTrue(dto.contains("channelSubjectId"), "AdminStoreDTO 应有 channelSubjectId");
        assertTrue(dto.contains("channelName"), "AdminStoreDTO 应有 channelName");
    }

    @Test
    void serviceShouldPopulateChannelName() throws Exception {
        String service = Files.readString(Paths.get(
                "src/main/java/com/wuling/subject/service/StoreService.java"));
        assertTrue(service.contains("setChannelName"), "toAdminStore 应回填 channelName");
        assertTrue(service.contains("getChannelSubjectId"), "应读取 profile.channelSubjectId");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd server && mvn -q -Dtest=AdminStoreChannelFieldTest test
```

预期：FAIL —— `AdminStoreDTO 应有 channelSubjectId` 失败。

- [ ] **Step 3: DTO 增加字段**

在 `server/src/main/java/com/wuling/subject/dto/AdminStoreDTO.java` 的 `investorSubjectId` 之后插入：

```java
    /** 门店唯一资源方（渠道）主体 ID，未绑定为 null */
    private Long channelSubjectId;
    /** 门店唯一资源方名称，未绑定为「未绑定」 */
    private String channelName;
```

- [ ] **Step 4: StoreService 回填资源方名称**

在 `server/src/main/java/com/wuling/subject/service/StoreService.java` 的 `toAdminStore` 方法中，`dto.setInvestorSubjectId(profile.getInvestorSubjectId());` 之后插入：

```java
            // 资源方（渠道）归属：与分佣归因同一列，未绑定展示「未绑定」
            dto.setChannelSubjectId(profile.getChannelSubjectId());
            if (profile.getChannelSubjectId() != null) {
                BizSubject channel = bizSubjectMapper.selectById(profile.getChannelSubjectId());
                dto.setChannelName(channel == null ? "未绑定" : channel.getName());
            } else {
                dto.setChannelName("未绑定");
            }
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd server && mvn -q -Dtest=AdminStoreChannelFieldTest test
```

预期：PASS（2 个用例全绿）。

- [ ] **Step 6: 编译确认**

```bash
mvn -q -DskipTests compile
```

预期：BUILD SUCCESS。

- [ ] **Step 7: 提交**

```bash
git add server/src/main/java/com/wuling/subject/dto/AdminStoreDTO.java server/src/main/java/com/wuling/subject/service/StoreService.java server/src/test/java/com/wuling/subject/AdminStoreChannelFieldTest.java
git commit -m "feat(subject): 门店列表回填关联资源方"
```

---

### Task 5: 前端门店页 —— 资源方列与绑定/解绑

**Files:**

- Modify: `src/service/api/subject.ts`
- Modify: `src/views/subject/store/index.vue`
- Test: `scripts/store-channel-ui.test.mjs`

**Interfaces:**

- Consumes: Task 3 三个后端接口、Task 4 `channelName/channelSubjectId`。
- Produces: `bindStoreChannel(storeSubjectId, channelSubjectId)`、`unbindStoreChannel(storeSubjectId)`、`fetchBindableStores(channelSubjectId)`。

- [ ] **Step 1: 写失败测试**

创建 `scripts/store-channel-ui.test.mjs`：

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const storePage = readFileSync(new URL('../src/views/subject/store/index.vue', import.meta.url), 'utf8');
const api = readFileSync(new URL('../src/service/api/subject.ts', import.meta.url), 'utf8');

// 1) 门店列表必须有「关联资源方」列
assert.ok(storePage.includes('关联资源方'), '门店列表应有「关联资源方」列');
assert.ok(storePage.includes('channelName'), '列应取 channelName 字段');

// 2) 门店页必须有绑定/解绑资源方操作
assert.ok(storePage.includes('绑定资源方'), '门店页应有「绑定资源方」操作');
assert.ok(storePage.includes('解绑资源方'), '门店页应有「解绑资源方」操作');
assert.ok(storePage.includes('bindStoreChannel'), '绑定应调用 bindStoreChannel');
assert.ok(storePage.includes('unbindStoreChannel'), '解绑应调用 unbindStoreChannel');

// 3) API 层必须暴露三个接口
assert.ok(api.includes('bindStoreChannel'), 'API 应暴露 bindStoreChannel');
assert.ok(api.includes('unbindStoreChannel'), 'API 应暴露 unbindStoreChannel');
assert.ok(api.includes('fetchBindableStores'), 'API 应暴露 fetchBindableStores');

console.log('store-channel-ui: all assertions passed');
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node scripts/store-channel-ui.test.mjs
```

预期：FAIL —— `门店列表应有「关联资源方」列`（AssertionError）。

- [ ] **Step 3: API 层新增接口**

在 `src/service/api/subject.ts` 的 `unbindChannelStores` 之后插入：

```ts
/** 门店绑定资源方（一店一资源方；已绑其他资源方时后端返回明确错误） */
export async function bindStoreChannel(storeSubjectId: number, channelSubjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/store/${storeSubjectId}/channel/${channelSubjectId}`,
    method: 'post'
  });
  return (res as any)?.data ?? res;
}

/** 门店解绑资源方（清空唯一归属 + 逻辑删除镜像行） */
export async function unbindStoreChannel(storeSubjectId: number): Promise<void> {
  const res = await request<void>({
    url: `/api/v1/admin/subject/binding/store/${storeSubjectId}/channel`,
    method: 'delete'
  });
  return (res as any)?.data ?? res;
}

/** 查询某资源方可绑定的门店（未绑定任何资源方，或已绑定当前资源方） */
export async function fetchBindableStores(channelSubjectId: number): Promise<any[]> {
  const res = await request<any[]>({
    url: `/api/v1/admin/subject/binding/channel/${channelSubjectId}/bindable-stores`
  });
  return (res as any)?.data ?? res;
}
```

- [ ] **Step 4: 门店页新增列与操作**

修改 `src/views/subject/store/index.vue`：

4a. 在 `import { geocodeAddress } from '@/utils/tencent-map';` 之后新增导入：

```ts
import { bindStoreChannel, unbindStoreChannel } from '@/service/api/subject';
```

4b. 在 `columns` 数组的「关联投资人」列之后插入资源方列：

```ts
  { title: '关联资源方', key: 'channelName', width: 120, render: (row: any) => row.channelName || '未绑定' },
```

4c. 在 `rowActions` 数组中「解绑投资人」之后插入两个操作：

```ts
  {
    label: '绑定资源方',
    type: 'info',
    picker: {
      title: '选择资源方',
      options: () =>
        store.subjects.filter(s => s.type === 'resource').map(s => ({ label: s.name, value: String(s.id) }))
    },
    handler: async (row, picked) => {
      if (picked) await bindStoreChannel(row.id, Number(picked));
    },
    visible: row => !row.channelSubjectId
  },
  {
    label: '解绑资源方',
    type: 'warning',
    reasonPrompt: '确认解绑该门店的资源方？（请填写备注）',
    handler: async row => await unbindStoreChannel(row.id),
    visible: row => Boolean(row.channelSubjectId)
  },
```

> **注意**：`store.subjects` 里资源方主体的 `type` 是归一化后的 `'resource'`（见 `src/store/modules/admin/index.ts` 的 `CHANNEL: 'resource'` 映射），不是 `'channel'`，写错会得到空下拉。

- [ ] **Step 5: 运行测试确认通过**

```bash
node scripts/store-channel-ui.test.mjs
```

预期：PASS —— 输出 `store-channel-ui: all assertions passed`。

- [ ] **Step 6: 类型检查与 Vue 单根校验**

```bash
npm run typecheck
npm run check:vue-root
```

预期：两条命令均无错误输出。

- [ ] **Step 7: 提交**

```bash
git add src/service/api/subject.ts src/views/subject/store/index.vue scripts/store-channel-ui.test.mjs
git commit -m "feat(web): 门店管理页资源方列与绑定解绑"
```

---

### Task 6: 前端资源方弹窗 —— 下拉过滤已占用门店

**Files:**

- Modify: `src/views/subject/channel/ChannelStoreDialog.vue`
- Test: `scripts/channel-store-dialog.test.mjs`

**Interfaces:**

- Consumes: Task 5 `fetchBindableStores`。
- Produces: 绑定下拉数据源改为后端 `bindable-stores`，不再依赖 `store.subjects` 镜像。

- [ ] **Step 1: 写失败测试**

创建 `scripts/channel-store-dialog.test.mjs`：

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const dialog = readFileSync(new URL('../src/views/subject/channel/ChannelStoreDialog.vue', import.meta.url), 'utf8');

// 1) 下拉数据源必须走后端可绑门店接口，避免镜像分页导致漏过滤
assert.ok(dialog.includes('fetchBindableStores'), '下拉应使用 fetchBindableStores');

// 2) 不得再仅靠 store.subjects 镜像过滤
assert.ok(!/const bindOptions[\s\S]{0,400}?store\.subjects/.test(dialog), 'bindOptions 不应再依赖 store.subjects 镜像');

// 3) 保留已绑定门店过滤（同店不重复出现）
assert.ok(dialog.includes('boundIds'), '应继续排除已绑定门店');

console.log('channel-store-dialog: all assertions passed');
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node scripts/channel-store-dialog.test.mjs
```

预期：FAIL —— `下拉应使用 fetchBindableStores`。

- [ ] **Step 3: 改造弹窗下拉**

修改 `src/views/subject/channel/ChannelStoreDialog.vue`：

3a. 导入新增接口：

```ts
import { fetchChannelStores, fetchBindableStores, unbindChannelStores } from '@/service/api/subject';
```

3b. 新增可绑门店状态与加载函数（放在 `boundStores` 声明之后）：

```ts
/** 可绑门店（后端过滤：未绑定任何资源方，或已绑定当前资源方） */
const bindableStores = ref<any[]>([]);

async function reloadBindable() {
  if (!props.channel?.id) return;
  try {
    bindableStores.value = await fetchBindableStores(props.channel.id);
  } catch (error: any) {
    window.$message?.error(error?.message || '可绑门店加载失败');
    bindableStores.value = [];
  }
}
```

3c. 在 `watch` 的 `if (props.show) { ... }` 分支内，`reload();` 之后补：

```ts
reloadBindable();
```

3d. 替换 `bindOptions` 计算属性为：

```ts
/**
 * 待绑定可选门店 = 后端返回的可绑门店 − 已绑定门店。
 *
 * 后端已排除「被其他资源方占用」的门店；这里再排除本资源方已绑的，
 * 保证下拉里不会出现重复项。不用 store.subjects 镜像，因其受分页 size=200 限制会漏数据。
 */
const bindOptions = computed(() => {
  const boundIds = new Set(boundStores.value.map((s: any) => Number(s.id)));
  return bindableStores.value
    .filter((s: any) => !boundIds.has(Number(s.id)))
    .map((s: any) => ({ label: s.name, value: String(s.id) }));
});
```

3e. 在 `doBind` 的 `await reload();` 之后补 `await reloadBindable();`：

```ts
await bindChannelStore(props.channel.id, Number(pickedStoreId.value));
window.$message?.success('绑定成功');
pickedStoreId.value = null;
await reload();
await reloadBindable();
emit('changed');
```

- [ ] **Step 4: 运行测试确认通过**

```bash
node scripts/channel-store-dialog.test.mjs
```

预期：PASS —— 输出 `channel-store-dialog: all assertions passed`。

- [ ] **Step 5: 类型检查**

```bash
npm run typecheck
```

预期：无错误。

- [ ] **Step 6: 提交**

```bash
git add src/views/subject/channel/ChannelStoreDialog.vue scripts/channel-store-dialog.test.mjs
git commit -m "fix(web): 资源方绑定门店下拉过滤已占用门店"
```

---

### Task 7: 全链路回归验证

**Files:**

- Create: `server/src/test/java/com/wuling/finance/StoreChannelSplitAttributionIT.java`

**Interfaces:**

- Consumes: Task 1-6 全部产出。
- Produces: 归因切换 / 漏算防护 / 守恒三组回归证据。

- [ ] **Step 1: 写回归测试**

创建 `server/src/test/java/com/wuling/finance/StoreChannelSplitAttributionIT.java`：

```java
package com.wuling.finance;

import com.wuling.finance.entity.SplitRule;
import com.wuling.finance.service.SplitCalculator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分佣归因回归（代码不变量 + 计算方法）。
 *
 * <p>覆盖需求方确认的验收：
 * 1) 归因不得再依赖 channel_store；
 * 2) 门店侧绑定必须写归因列；
 * 3) 分账守恒。
 *
 * <p>数据库级行为（改绑后 selectBoundChannel 返回新值）按「人工回归脚本」执行。
 */
class StoreChannelSplitAttributionIT {

    @Test
    void attributionMustNotDependOnChannelStore() throws Exception {
        String mapper = Files.readString(Paths.get(
                "trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java"));
        assertFalse(mapper.contains("channel_store"), "归因不得再读 channel_store（R1/R3 根因）");
        assertTrue(mapper.contains("store_profile"), "归因必须读 store_profile.channel_subject_id");
    }

    @Test
    void storeSideBindMustWriteAttributionColumn() throws Exception {
        String controller = Files.readString(Paths.get(
                "server/src/main/java/com/wuling/subject/controller/SubjectBindingController.java"));
        assertTrue(controller.contains("update store_profile set channel_subject_id"),
                "门店侧绑定必须写归因列（R2 根因）");
    }

    @Test
    void splitCalculatorHonoursHasChannelAndKeepsConservation() {
        SplitCalculator calculator = new SplitCalculator();
        SplitRule rule = new SplitRule();
        rule.setStoreRatio(300);
        rule.setChannelRatio(100);
        rule.setInvestorRatio(1500);

        SplitCalculator.SplitAmount withChannel = calculator.calc(2000L, 2, true, rule, 0L, List.of());
        SplitCalculator.SplitAmount without = calculator.calc(2000L, 2, false, rule, 0L, List.of());

        // 有归因：资源方 100 × 2 = 200
        assertEquals(200L, withChannel.channel(), "有归因时资源方应得 200 分");
        // 无归因：资源方 0，该份额进平台尾差
        assertEquals(0L, without.channel(), "无归因时资源方应为 0");
        // 守恒：五方之和恒等于实付
        assertEquals(2000L, sum(withChannel), "有归因时五方之和应为实付");
        assertEquals(2000L, sum(without), "无归因时五方之和应为实付");
    }

    private long sum(SplitCalculator.SplitAmount a) {
        return a.platform() + a.store() + a.channel() + a.investor() + a.supplier();
    }
}
```

- [ ] **Step 2: 运行回归测试**

```bash
cd server && mvn -q -Dtest=StoreChannelSplitAttributionIT test
```

预期：PASS（3 个用例全绿）。若 `attributionMustNotDependOnChannelStore` 失败，说明 Task 2 未落地，需回退修复。

- [ ] **Step 3: 运行分账既有测试，确认未破坏**

```bash
cd server && mvn -q -Dtest='SplitCalculatorTest,LedgerServiceTest,InvestorThresholdTest,LedgerConsistencyIT' test
```

预期：全部 PASS。

- [ ] **Step 4: 运行后端全量单测**

```bash
cd server && mvn -q test
```

预期：BUILD SUCCESS，无失败用例。

- [ ] **Step 5: 前端全量校验**

```bash
node scripts/store-channel-ui.test.mjs
node scripts/channel-store-dialog.test.mjs
npm run typecheck
npm run check:vue-root
```

预期：两条脚本输出 passed，两条 npm 命令无错误。

- [ ] **Step 6: 人工回归脚本（部署环境执行并记录结果）**

按顺序验证，每步记录实际结果：

1. **列表展示**：门店管理页出现「关联资源方」列；种子数据中门店 101/102 显示资源方 301 的名称，103 显示 302，104 显示 303。
2. **改绑归因**：门店 101 改绑资源方 302 → 新下单 → 查 `order.channel_subject_id` 应为 302（历史订单仍为 301）。
3. **漏算防护**：仅在门店管理页给门店 105 绑资源方 301（不手工写 `channel_store`）→ 下单 → `order.channel_subject_id` 应为 301。
4. **解绑生效**：解绑门店 105 → 下单 → `channel_subject_id` 为 null → 该单 `channel_amount = 0`。
5. **下拉过滤**：资源方 303 打开「绑定门店」→ 下拉不出现门店 101（已被 301/302 占用）。
6. **数据一致**：执行校验 SQL，应返回 0 行：

```sql
SELECT cs.store_subject_id
FROM channel_store cs
LEFT JOIN store_profile sp ON sp.subject_id = cs.store_subject_id
WHERE cs.deleted = 0
  AND (sp.channel_subject_id IS NULL OR sp.channel_subject_id <> cs.channel_subject_id);
```

7. **守恒复核**：任取一条新核销订单，`平台+门店+资源方+投资人+供应商 == 实付`（`split_snapshot.total_check` 应为「一致」）。

- [ ] **Step 7: 提交**

```bash
git add server/src/test/java/com/wuling/finance/StoreChannelSplitAttributionIT.java
git commit -m "test(finance): 门店资源方归因与守恒回归"
```

---

## 交付清单

| 层   | 文件                                                      | 改动                                             |
| ---- | --------------------------------------------------------- | ------------------------------------------------ |
| DB   | `V58__store_single_channel.sql`                           | 新增列 + 回填 + 索引                             |
| 后端 | `OrderMapper.java`                                        | 归因改读 `store_profile` ⭐                      |
| 后端 | `BizSubjectMapper.java`                                   | 渠道门店列表改源 + 新增 `selectChannelOfStore`   |
| 后端 | `SubjectQueryPort.java` / `LocalSubjectQueryAdapter.java` | +`findChannelOfStore`                            |
| 后端 | `AdminSubjectProfileController.java`                      | 资源方门店数改源                                 |
| 后端 | `StoreProfile.java`                                       | +`channelSubjectId`                              |
| 后端 | `AdminStoreDTO.java` / `StoreService.java`                | +`channelName`/`channelSubjectId` 并回填         |
| 后端 | `SubjectBindingController.java`                           | 双写 + 占用校验 + 门店侧绑定/解绑 + 可绑门店接口 |
| 前端 | `src/service/api/subject.ts`                              | +3 接口                                          |
| 前端 | `src/views/subject/store/index.vue`                       | +资源方列 +绑定/解绑                             |
| 前端 | `src/views/subject/channel/ChannelStoreDialog.vue`        | 下拉改走后端过滤                                 |
| 测试 | 5 个 Java 测试 + 2 个 Node 脚本                           | 静态规则 + 计算方法回归                          |

## 不做的事（明确排除）

- 不删除 `channel_store` 表（过渡期兼容镜像，后续版本再评估下线）。
- 不回算历史 `split_snapshot`。
- 不修改 `SplitCalculator` / `SplitRule` 计算逻辑（固定金额模型不变）。
- 不引入新的 UI 组件库或图标资源。

## 风险与回滚

- **风险 1**：V58 依赖 `DELIMITER $$` 存储过程语法。本仓库既有 `V12__concurrency_safety.sql` 已用同一写法并通过生产迁移，Flyway 版本支持；若部署环境报语法错误，退化为裸 `ALTER TABLE`（首次必成功，重复执行靠 `flyway_schema_history` 防重）。
- **风险 2**：`channel_store` 存在历史脏数据（同门店多条有效行）时回填取 id 最小者，可能与预期不符。执行 Task 7 Step 6 的校验 SQL 确认偏差。
- **回滚**：Task 2 之后若需回滚，需同时回滚 `OrderMapper` 与 `BizSubjectMapper` 的 SQL，否则出现「归因读新源、列表读旧源」的不一致；V58 加列为可空，回滚代码后不影响运行。
