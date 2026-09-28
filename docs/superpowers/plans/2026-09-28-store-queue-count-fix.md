# 门店「前方N杯制作中」口径修正实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修正 `GET /api/v1/app/stores` 的 `queueCount` 口径，让点单页「前方N杯制作中」只统计**近期真实在制**的杯数，而不是历史累计已核销订单数。

**Architecture:** 口径下沉到 `trade-service` 的单条聚合 SQL（`OrderMapper`），`server` 的 `StoreService` 仅做透传；`RemoteTradeOrderQueryAdapter` 已按「下游不可用返回 0」降级，无需改动。时间窗由自然日改为**滑动 1 小时**（`pay_time >= now() - 1h`）。

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / MySQL；微信小程序原生。

**Spec:** 用户需求（本会话）：线上「星沙乐运魔方店」显示「前方28杯制作中」数据错误；该数据来自后端 `queueCount`；已确认口径为 `pay_time >= now() - 1 小时`。

---

## 现状与风险基线（实测结论）

- **线上数字确认**：`GET http://43.136.91.239:8089/api/v1/app/stores` 返回 `星沙乐运魔方店 queueCount=28`，与截图一致。
- **根因 1 —— 部署滞后**：该实例 `/internal/store-queue-count` 返回 **404**，说明跑的是 `aa0bb66` 之前的旧构建，仍执行老逻辑 `status=COMPLETED AND complete_time IS NULL`。
- **根因 2 —— 老逻辑语义错误**：奶茶「核销即取餐」，`VerifyService#markVerified` 只写 `status=COMPLETED` + `verify_time`，**从不写 `complete_time`**。于是「已核销未取餐」退化为「历史全部已核销订单」。本地库跑同一 SQL 精确得到 **28**，跨 09-14 ~ 09-27 共两周。
- **根因 3 —— 新逻辑仍不精确**：`aa0bb66` 改为 `create_time >= 今日0点`，会把「昨天下单、现在仍在做」的跨天单在 00:00 清零，且堂食单计入自提排队。
- **死字段**：`store_profile.queue_count` 列存在但从不读写，仅 `AppStoreDTO.queueCount` 走实时查询。
- **测试脱节**：`TradeInternalQueryControllerTest` 仍用旧双参构造器（`OrderMapper, OrderItemMapper`），编译不过，已失去回归保护能力。

---

## 任务总览（严格按序执行）

1. Task 1：`OrderMapper` 口径改为滑动 1 小时窗口
2. Task 2：`StoreService` 补注释与降级日志，消除死字段误导
3. Task 3：修复并强化 `TradeInternalQueryControllerTest`（防回归）
4. Task 4：本地编译 + 单测验证
5. Task 5：重新部署 trade-service 并线上验证

---

## Global Constraints

- 时间窗**必须**由后端计算（`LocalDateTime.now().minusHours(1)`），不得依赖数据库 `now()`，保证与 `pay_time` 写入时区（`Asia/Shanghai`）一致。
- 口径只统计 `status = 'PAID'`（已支付待核销）且非退款中（`refund_status IS NULL OR <> 'PENDING'`）。
- 返回 `0` 时前端不展示标签（`user-h5/utils/store.js#getQueueStatus` 对 `count<=0` 返回空文案）——**前端零改动**。
- 不新增接口、不改 `AppStoreDTO` 字段名，避免小程序契约变更。
- 每任务结束独立可验证；每任务一次提交。

---

## Task 1：`OrderMapper` 口径改为滑动 1 小时窗口

**Files:**
- Modify: `trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java`

- [ ] Step 1：把 `sumTodayPendingVerifyQuantity(storeSubjectId, dayStart)` 重命名为语义正确的 `sumRecentPendingVerifyQuantity(storeSubjectId, windowStart)`，SQL 条件由 `o.create_time >= #{dayStart}` 改为 `o.pay_time >= #{windowStart}`。

```java
/** 近 1 小时待核销订单的杯数（商品件数求和）：状态 PAID、非退款中、支付时间在窗口内 */
@Select("select coalesce(sum(i.quantity), 0) from order_item i "
        + "join orders o on o.id = i.order_id and o.deleted = 0 "
        + "where i.deleted = 0 and o.store_subject_id = #{storeSubjectId} "
        + "and o.status = 'PAID' "
        + "and o.pay_time >= #{windowStart} "
        + "and (o.refund_status is null or o.refund_status <> 'PENDING')")
Long sumRecentPendingVerifyQuantity(@Param("storeSubjectId") Long storeSubjectId,
                                    @Param("windowStart") java.time.LocalDateTime windowStart);
```

- [ ] Step 2：提交 `fix(trade): 门店排队杯数改为近1小时滑动窗口口径`

---

## Task 2：`StoreService` 补注释与降级日志

**Files:**
- Modify: `server/src/main/java/com/wuling/subject/service/StoreService.java`

- [ ] Step 1：`countPendingQueue` 传入 `LocalDateTime.now().minusHours(1)`，`catch` 分支补 `log.warn`（当前静默吞异常，线上表现为「数字突然变 0」，极难排查）。

- [ ] Step 2：修正方法注释——原文「已核销未取餐」是错误语义，改为「近 1 小时已支付待核销杯数」。

- [ ] Step 3：在 `StoreService` 类注释说明 `store_profile.queue_count` 为历史遗留死字段、实时值不落库；并在 `app_store` 相关文档（`docs/data-schema.md` 若存在该列）标注弃用。

- [ ] Step 4：提交 `fix(subject): 门店排队件数注释与降级日志修正`

---

## Task 3：修复并强化 `TradeInternalQueryControllerTest`

**Files:**
- Modify: `trade-service/src/test/java/com/wuling/trade/internal/TradeInternalQueryControllerTest.java`

- [ ] Step 1：构造器改回单参 `new TradeInternalQueryController(orderMapper)`（当前编译不过）。

- [ ] Step 2：原用例 `queueCountUsesCompletedStatusAsWorkInProgress` 断言的 `OrderItemMapper` 桩已失效，重写为两个用例：
  1. **回归用例**：`status=COMPLETED` 且 `complete_time IS NULL` 的订单**不计入**（正是本次事故的坑）；
  2. **正例**：`status=PAID` 的今日订单按 `Σ quantity` 计入。

- [ ] Step 3：提交 `test(trade): 修复排队杯数单测并补 COMPLETED 防回归用例`

---

## Task 4：本地编译 + 单测验证

- [ ] Step 1：`mvn -q -pl trade-service,server -am -DskipTests=false test`，确认 trade-service 单测通过。
- [ ] Step 2：确认 `server` 模块编译通过（`StoreService` 改签名后无残留调用点）。

---

## Task 5：重新部署 trade-service 并线上验证

- [ ] Step 1：`./deploy-backend.sh build && ./deploy-backend.sh up`（目标机 `43.136.91.239`）。
- [ ] Step 2：验证内部接口已存在：`curl "http://127.0.0.1:8084/internal/store-queue-count?storeSubjectId=101"` 返回 `{"count":N}` 而非 404。
- [ ] Step 3：验证小程序接口：`curl "http://43.136.91.239:8089/api/v1/app/stores"`，101 号店 `queueCount` 等于「近 1 小时 PAID 非退款中杯数之和」。
- [ ] Step 4：造数验证：下单支付后立即刷新点单页，`queueCount` 应 +N；1 小时后自然回落。

---

## 验收标准

- 101 号店 `queueCount` 与「近 1 小时 `PAID` 非退款中订单的 `Σ quantity`」逐笔核对一致。
- 近 1 小时无单时返回 `0`，前端不展示「前方N杯制作中」（现有行为，无需改前端）。
- 历史 `COMPLETED` 订单不再产生任何计数（回归用例覆盖）。
- `/internal/store-queue-count` 在目标环境返回 200。

## 未决 / 后续（不在本次范围）

- 堂食单（`meal_type='dinein'`）是否计入自提排队：本次沿用「全部计入」，如需拆分另开任务。
- `store_profile.queue_count` 列是否物理删除：待确认无第三方消费后再做 DDL 清理。