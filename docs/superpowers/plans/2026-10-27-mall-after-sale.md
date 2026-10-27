# 商城售后（退货退款）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为已发货/已完成商城订单补齐“部分退货退款、审核、退款、库存回补、财务冲正、用户端查询、管理端审核”的完整闭环。

**Architecture:** 新增独立 `mall_after_sale` 聚合，售后单保存本次退货明细与服务端计算的金额；审核通过后创建关联退款单，复用既有微信退款回调/重试/对账链路。退款成功只回补本次退货数量，按本次退款比例发布财务冲正，整单全部退完才把订单置 `REFUNDED`；库存幂等从订单号升级为“订单号校验 + 业务号幂等”。

**Tech Stack:** Spring Boot 3 / MyBatis-Plus / MySQL / RabbitMQ Outbox / JUnit 5 + Mockito / Vue 3 + Naive UI / 微信小程序原生 Skyline / Node test runner。

**Spec:** `docs/调整方案-小程序商城.md`（本计划细化售后章节，并遵守 `AGENTS.md` 与 `user-h5/docs/design-system.md`）

## Global Constraints

- 保留工作区现有未提交改动，不清理、不回滚无关文件；不得使用 `git add .`。
- 所有金额单位为分；退款金额必须由服务端按订单明细 `unitPrice × quantity` 计算，禁止信任客户端金额。
- 已发货/已完成商城订单可申请退货退款；支持部分商品、部分数量和后续继续申请剩余商品。
- 售后状态：`PENDING -> APPROVED / REJECTED`；审核通过后进入 `REFUNDING -> SUCCESS / FAILED`。
- 同一订单同一时刻最多一个处理中售后单；`PENDING/APPROVED/REFUNDING` 均视为处理中。
- 退款失败后售后单为 `FAILED`，订单保持原业务状态，允许复用现有退款重试接口。
- 退款成功：售后单 `SUCCESS`；只回补本次数量；部分退款保持订单 `SHIPPED/COMPLETED`，仅 `refundStatus=REFUNDED`；全部数量退完才置订单 `REFUNDED`；只有整单全退才恢复优惠券。
- 保留发货前 `PAID` 订单整单退款/取消旧链路，禁止与售后链路重复退款。
- 小程序 UI 只能使用 `app.wxss` 的 token、设计文档允许的间距和 rpx；禁止原生 `<button>`、第三方 UI、远程 CDN、手写 SVG、emoji 业务图标。
- 图标先改 `user-h5/scripts/sync-lucide-icons.mjs`，再执行 `npm run icons`；页面完成后执行 `npm run check`。
- 后端新增受保护接口必须同步 `TradeSecurityConfig`、`GatewayAuthPolicy` 与 `DedicatedEndpointConsistencyTest`。

---

### Task 1: 售后领域模型与请求/视图契约

**Files:**

- Modify: `trade-service/src/main/java/com/wuling/trade/entity/MallAfterSale.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/dto/MallAfterSaleVO.java`
- Create: `trade-service/src/main/java/com/wuling/trade/dto/CreateAfterSaleRequest.java`
- Create: `trade-service/src/main/java/com/wuling/trade/dto/AfterSaleReviewRequest.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/mapper/MallAfterSaleMapper.java`
- Test: `trade-service/src/test/java/com/wuling/trade/dto/AfterSaleContractTest.java`

**Interfaces:**

- Produces: `MallAfterSale` in package `com.wuling.trade.entity`; `MallAfterSaleVO` in `com.wuling.trade.dto`; `CreateAfterSaleRequest` with `reason` and `List<Item>{orderItemId,quantity}`; `AfterSaleReviewRequest` with `remark`; mapper helpers `selectActiveByOrderIdForUpdate`, `selectByAfterSaleNo`, `sumRefundedQuantity(orderId)`.

- [ ] **Step 1: Write the failing contract test**

```java
class AfterSaleContractTest {
    @Test
    void requestAndViewUseExpectedPackagesAndFields() throws Exception {
        assertEquals("com.wuling.trade.dto.MallAfterSaleVO",
                Class.forName("com.wuling.trade.dto.MallAfterSaleVO").getName());
        assertEquals("com.wuling.trade.entity.MallAfterSale",
                Class.forName("com.wuling.trade.entity.MallAfterSale").getName());
        assertNotNull(CreateAfterSaleRequest.class.getDeclaredField("items"));
        assertNotNull(AfterSaleReviewRequest.class.getDeclaredField("remark"));
    }
}
```

- [ ] **Step 2: Run the failing test**

Run: `mvn -q -pl trade-service -am -Dtest=AfterSaleContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because `MallAfterSaleVO` is currently in the wrong package / request DTOs are missing.

- [ ] **Step 3: Implement the contracts**

Move `MallAfterSaleVO` to `com.wuling.trade.dto`; add bean-validation annotations to request DTOs; keep JSON `items` storage as a dedicated `MallAfterSale.items` string and expose parsed `MallAfterSaleVO.items`. Add mapper methods for active-row locking and refunded-quantity aggregation.

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl trade-service -am -Dtest=AfterSaleContractTest -Dsurefire.checkstyle.skip=false test`
Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add trade-service/src/main/java/com/wuling/trade/entity/MallAfterSale.java trade-service/src/main/java/com/wuling/trade/dto/MallAfterSaleVO.java trade-service/src/main/java/com/wuling/trade/dto/CreateAfterSaleRequest.java trade-service/src/main/java/com/wuling/trade/dto/AfterSaleReviewRequest.java trade-service/src/main/java/com/wuling/trade/mapper/MallAfterSaleMapper.java trade-service/src/test/java/com/wuling/trade/dto/AfterSaleContractTest.java
git commit -m "feat: add mall after-sale contracts"
```

---

### Task 2: 申请、审核、查询服务与并发约束

**Files:**

- Create: `trade-service/src/main/java/com/wuling/trade/service/MallAfterSaleService.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/mapper/MallAfterSaleMapper.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/mapper/OrderItemMapper.java`
- Test: `trade-service/src/test/java/com/wuling/trade/service/MallAfterSaleServiceTest.java`

**Interfaces:**

- Consumes: Task 1 request/view types; `OrderMapper`, `OrderItemMapper`, `MallAfterSaleMapper`.
- Produces: `create(Long userId, String orderNo, CreateAfterSaleRequest)`, `listByOrder(Long userId, String orderNo)`, `listByUser(Long userId, long current, long size)`, `detail(Long userId, String afterSaleNo)`, `approve(String afterSaleNo, String reviewer, String remark)`, `reject(String afterSaleNo, String reviewer, String remark)`.

- [ ] **Step 1: Write failing service tests**

Cover these exact assertions in `MallAfterSaleServiceTest`:

```java
@Test void createRejectsOrderBeforeShipment();
@Test void createRejectsForeignOrder();
@Test void createAllowsPartialQuantityAndComputesAmountFromSnapshots();
@Test void createRejectsQuantityAboveRemainingRefundableQuantity();
@Test void createRejectsSecondActiveAfterSale();
@Test void approveCreatesRefundThroughRefundServiceAndMovesToRefunding();
@Test void rejectRequiresPendingAndWritesReviewer();
@Test void detailRejectsAnotherUsersAfterSale();
```

Use an order item `unitPrice=9900`, requested quantity `1`, and assert `refundAmount=9900`, never read a client-supplied amount.

- [ ] **Step 2: Run the failing tests**

Run: `mvn -q -pl trade-service -am -Dtest=MallAfterSaleServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because `MallAfterSaleService` does not exist.

- [ ] **Step 3: Implement the service**

Implementation rules:

```java
private static final Set<String> ACTIVE = Set.of("PENDING", "APPROVED", "REFUNDING");
private static final Set<String> ELIGIBLE_ORDER_STATUS = Set.of("SHIPPED", "COMPLETED");

// create:
// 1. lock order and assert userId/orderType/status/refundStatus;
// 2. lock the active after-sale row for the order (or assert no active row);
// 3. load order items by orderId and id; reject foreign/missing items;
// 4. aggregate requested quantities per orderItemId;
// 5. subtract successful + in-flight after-sale quantities;
// 6. compute each lineAmount = unitPrice * quantity and refundAmount = sum;
// 7. insert PENDING with a generated AS number and JSON items snapshot.
```

Approve must lock the after-sale row, transition only `PENDING -> APPROVED`, then call `refundService.refundAfterSale(order, afterSale, remark)`; if the refund call returns a refund id, update `REFUNDING` and `refundId`. Reject only `PENDING -> REJECTED`. Never allow admin-supplied amount or status.

- [ ] **Step 4: Run the service tests**

Run: `mvn -q -pl trade-service -am -Dtest=MallAfterSaleServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add trade-service/src/main/java/com/wuling/trade/service/MallAfterSaleService.java trade-service/src/main/java/com/wuling/trade/mapper/MallAfterSaleMapper.java trade-service/src/main/java/com/wuling/trade/mapper/OrderItemMapper.java trade-service/src/test/java/com/wuling/trade/service/MallAfterSaleServiceTest.java
git commit -m "feat: implement mall after-sale application flow"
```

---

### Task 3: 用户端与管理端接口及鉴权

**Files:**

- Create: `trade-service/src/main/java/com/wuling/trade/controller/AppAfterSaleController.java`
- Create: `trade-service/src/main/java/com/wuling/trade/controller/AdminAfterSaleController.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/config/TradeSecurityConfig.java`
- Modify: `gateway/src/main/java/com/wuling/gateway/security/GatewayAuthPolicy.java`
- Modify: `server/src/test/java/com/wuling/common/DedicatedEndpointConsistencyTest.java`
- Test: `trade-service/src/test/java/com/wuling/trade/controller/AppAfterSaleControllerTest.java`
- Test: `trade-service/src/test/java/com/wuling/trade/controller/AdminAfterSaleControllerTest.java`

**Interfaces:**

- Consumes: Task 2 service methods.
- Produces exact endpoints:
  - `POST /api/v1/app/mall/orders/{orderNo}/after-sales`
  - `GET /api/v1/app/mall/orders/{orderNo}/after-sales`
  - `GET /api/v1/app/mall/after-sales`
  - `GET /api/v1/app/mall/after-sales/{afterSaleNo}`
  - `GET /api/v1/admin/trade/after-sales`
  - `GET /api/v1/admin/trade/after-sales/{afterSaleNo}`
  - `POST /api/v1/admin/trade/after-sales/{afterSaleNo}/approve`
  - `POST /api/v1/admin/trade/after-sales/{afterSaleNo}/reject`

- [ ] **Step 1: Write failing controller and policy tests**

```java
@Test void userCannotReadAnotherUsersAfterSale();
@Test void adminApproveUsesJwtOperatorAndIgnoresClientAmount();
@Test void appAfterSaleEndpointsAreProtectedByMiniAppAuth();
@Test void adminAfterSaleEndpointsUseAdminAuth();
```

- [ ] **Step 2: Run the failing tests**

Run: `mvn -q -pl trade-service,server -am -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,DedicatedEndpointConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because controllers and path registrations are missing.

- [ ] **Step 3: Implement controllers and path policy**

Use `CurrentUser.require()` for user endpoints and `AdminUser.require()` for reviewer identity. Return `Result<...>`/`PageResult<...>`. Add `/api/v1/app/mall/after-sales` and `/api/v1/app/mall/after-sales/**` to both auth policies, and register the new admin page/backend files in `DedicatedEndpointConsistencyTest`.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl trade-service,server -am -Dtest=AppAfterSaleControllerTest,AdminAfterSaleControllerTest,DedicatedEndpointConsistencyTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add trade-service/src/main/java/com/wuling/trade/controller/AppAfterSaleController.java trade-service/src/main/java/com/wuling/trade/controller/AdminAfterSaleController.java trade-service/src/main/java/com/wuling/trade/config/TradeSecurityConfig.java gateway/src/main/java/com/wuling/gateway/security/GatewayAuthPolicy.java server/src/test/java/com/wuling/common/DedicatedEndpointConsistencyTest.java trade-service/src/test/java/com/wuling/trade/controller/AppAfterSaleControllerTest.java trade-service/src/test/java/com/wuling/trade/controller/AdminAfterSaleControllerTest.java
git commit -m "feat: expose mall after-sale APIs"
```

---

### Task 4: 退款状态机、订单终态与部分退款

**Files:**

- Modify: `trade-service/src/main/java/com/wuling/trade/service/RefundService.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/service/OrderService.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java`
- Test: `trade-service/src/test/java/com/wuling/trade/service/RefundServiceStatusTest.java`
- Test: `trade-service/src/test/java/com/wuling/trade/service/OrderStatusLifecycleTest.java`

**Interfaces:**

- Consumes: Task 2 after-sale service/entity; Task 1 refund columns.
- Produces: `RefundService.refundAfterSale(Order order, MallAfterSale afterSale, String reason)`, `OrderService.markAfterSaleRefunded(Order order, long refundedQuantity, long totalQuantity)`, `OrderService.markAfterSaleRefundFailed(Order order)`.

- [ ] **Step 1: Write failing state tests**

Add assertions:

```java
@Test void wholeOrderRefundMovesOrderToRefundedNotCanceled();
@Test void partialAfterSaleSuccessKeepsShippedAndSetsRefundedStatus();
@Test void fullAfterSaleSuccessMovesOrderToRefunded();
@Test void afterSaleFailureKeepsShippedAndSetsFailed();
@Test void successPublishesActualRefundRatio();
@Test void fullRefundRestoresCouponButPartialDoesNot();
```

Use `refundRatio=5000` for a half refund and assert `OrderRefundedEvent.refundRatio == 5000`; assert `restoreForRefund` receives only the after-sale SKU/quantity.

- [ ] **Step 2: Run the failing tests**

Run: `mvn -q -pl trade-service -am -Dtest=RefundServiceStatusTest,OrderStatusLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL on `CANCELED`, missing ratio, and whole-order stock restoration.

- [ ] **Step 3: Implement state handling**

- Change whole-order `markRefunded` to write `REFUNDED`, not `CANCELED`.
- Add after-sale-specific order status methods that preserve `SHIPPED/COMPLETED` for partial success and write `REFUNDED` only when refunded quantity equals ordered quantity.
- In `markSuccess`, branch on `refund.afterSaleId`: restore only the linked after-sale lines; calculate `refundRatio = round(refund.amount * 10000 / order.paidAmount)`; set `event.refundRatio`; enqueue coupon restore only for a full refund.
- In `markFailed`, use the after-sale branch so `SHIPPED` is never downgraded to `PAID`; update the after-sale row to `FAILED`.
- `retry(refundId)` must retain `afterSaleId`/`originalAmount`, return the after-sale to `REFUNDING`, and never re-create a second refund.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl trade-service -am -Dtest=RefundServiceStatusTest,OrderStatusLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add trade-service/src/main/java/com/wuling/trade/service/RefundService.java trade-service/src/main/java/com/wuling/trade/service/OrderService.java trade-service/src/main/java/com/wuling/trade/mapper/OrderMapper.java trade-service/src/test/java/com/wuling/trade/service/RefundServiceStatusTest.java trade-service/src/test/java/com/wuling/trade/service/OrderStatusLifecycleTest.java
git commit -m "feat: complete mall after-sale refund state machine"
```

---

### Task 5: 库存业务号幂等与部分回补

**Files:**

- Create: `server/src/main/resources/db/migration/V89__mall_stock_biz_no.sql`
- Modify: `product-service/src/main/java/com/wuling/product/dto/MallStockMutationRequest.java`
- Modify: `product-service/src/main/java/com/wuling/product/entity/MallStockLog.java`
- Modify: `product-service/src/main/java/com/wuling/product/mapper/MallStockLogMapper.java`
- Modify: `product-service/src/main/java/com/wuling/product/service/MallInventoryService.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/port/MallCatalogPort.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/port/RemoteMallCatalogAdapter.java`
- Modify: `trade-service/src/main/java/com/wuling/trade/service/MallStockService.java`
- Test: `product-service/src/test/java/com/wuling/product/service/MallInventoryServiceTest.java`
- Test: `trade-service/src/test/java/com/wuling/trade/service/MallStockServiceTest.java`

**Interfaces:**

- Produces: `MallStockMutationRequest.bizNo`; stock log `bizNo`; `MallCatalogPort.restore(String orderNo, String bizNo, List<StockLine>)`; `MallStockService.restoreForAfterSale(Order, MallAfterSale)`.
- Compatibility: ordinary order operations default `bizNo = orderNo`.

- [ ] **Step 1: Write failing tests**

```java
@Test void twoAfterSalesForSameOrderRestoreSeparately();
@Test void sameAfterSaleRetriedRestoresOnlyOnce();
@Test void afterSaleRestoreStillRequiresOriginalDeductLog();
@Test void normalOrderRestoreKeepsOrderNoAsBizNo();
```

- [ ] **Step 2: Run the failing tests**

Run: `mvn -q -pl product-service,trade-service -am -Dtest=MallInventoryServiceTest,MallStockServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because `bizNo` does not exist and restore is keyed only by order number.

- [ ] **Step 3: Implement bizNo idempotency**

Add `biz_no` to `mall_stock_log`, backfill `biz_no = order_no`, and create a unique key on `(biz_no, change_type, sku_id)`. For `DEDUCT` validation keep `order_no`; for idempotency use `biz_no`. Pass the after-sale number as `bizNo` and only the after-sale lines/quantities. Do not let a failed remote call leave a local success marker.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl product-service,trade-service -am -Dtest=MallInventoryServiceTest,MallStockServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add server/src/main/resources/db/migration/V89__mall_stock_biz_no.sql product-service/src/main/java/com/wuling/product/dto/MallStockMutationRequest.java product-service/src/main/java/com/wuling/product/entity/MallStockLog.java product-service/src/main/java/com/wuling/product/mapper/MallStockLogMapper.java product-service/src/main/java/com/wuling/product/service/MallInventoryService.java trade-service/src/main/java/com/wuling/trade/port/MallCatalogPort.java trade-service/src/main/java/com/wuling/trade/port/RemoteMallCatalogAdapter.java trade-service/src/main/java/com/wuling/trade/service/MallStockService.java product-service/src/test/java/com/wuling/product/service/MallInventoryServiceTest.java trade-service/src/test/java/com/wuling/trade/service/MallStockServiceTest.java
git commit -m "feat: add after-sale stock idempotency"
```

---

### Task 6: 财务按比例冲正与消息幂等

**Files:**

- Create: `server/src/main/resources/db/migration/V90__settlement_refund_accumulation.sql`
- Modify: `server/src/main/java/com/wuling/finance/entity/SettlementRecord.java`
- Modify: `server/src/main/java/com/wuling/finance/mapper/SettlementRecordMapper.java`
- Modify: `server/src/main/java/com/wuling/finance/service/LedgerService.java`
- Modify: `server/src/main/java/com/wuling/finance/mq/OrderRefundedReverseConsumer.java`
- Modify: `wuling-common/src/main/java/com/wuling/common/mq/event/OrderRefundedEvent.java`
- Test: `server/src/test/java/com/wuling/finance/LedgerServiceTest.java`
- Test: `server/src/test/java/com/wuling/finance/mq/OrderRefundedReverseConsumerTest.java`

**Interfaces:**

- Consumes: `OrderRefundedEvent.refundNo`, `refundRatio`, `refundAmount`.
- Produces: stable message id `<orderNo>:<refundNo>:REVERSE`; cumulative settlement reversal using `originalAmount` and `refundedAmount`.

- [ ] **Step 1: Write failing tests**

```java
@Test void consumerDeduplicatesByRefundNoNotOnlyOrderNo();
@Test void twoPartialRefundsDeductTheirProportionalShares();
@Test void cumulativePartialRefundNeverExceedsOriginalAmount();
@Test void legacyFullRefundStillCancelsSettlement();
```

Assert a `1000` settlement with two `5000` ratios deducts `500 + 500`, not `500 + 250`.

- [ ] **Step 2: Run the failing tests**

Run: `mvn -q -pl server -am -Dtest=LedgerServiceTest,OrderRefundedReverseConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL because the second partial refund uses the reduced amount and the stable message id is order-only.

- [ ] **Step 3: Implement cumulative reversal**

Add nullable `original_amount` and `refunded_amount` to `settlement_record`; backfill `original_amount = amount`, `refunded_amount = 0`. On each reversal, compute `target = round(originalAmount * ratio / 10000)`, `delta = target - refundedAmount`, clamp to `[0, originalAmount]`, and update both `amount` and `refunded_amount`. Full refund (`ratio >= 10000`) remains `CANCELED`; partial refunds keep the original settlement status. Consumer must pass `event.getRefundRatio()` with a default of `10000` and use refund-specific stable message IDs.

- [ ] **Step 4: Run the tests**

Run: `mvn -q -pl server -am -Dtest=LedgerServiceTest,OrderRefundedReverseConsumerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add server/src/main/resources/db/migration/V90__settlement_refund_accumulation.sql server/src/main/java/com/wuling/finance/entity/SettlementRecord.java server/src/main/java/com/wuling/finance/mapper/SettlementRecordMapper.java server/src/main/java/com/wuling/finance/service/LedgerService.java server/src/main/java/com/wuling/finance/mq/OrderRefundedReverseConsumer.java wuling-common/src/main/java/com/wuling/common/mq/event/OrderRefundedEvent.java server/src/test/java/com/wuling/finance/LedgerServiceTest.java server/src/test/java/com/wuling/finance/mq/OrderRefundedReverseConsumerTest.java
git commit -m "fix: reverse partial refunds cumulatively"
```

---

### Task 7: 小程序售后页面与订单入口

**Files:**

- Create: `user-h5/packageMall/pages/after-sale/after-sale.js`
- Create: `user-h5/packageMall/pages/after-sale/after-sale.wxml`
- Create: `user-h5/packageMall/pages/after-sale/after-sale.wxss`
- Create: `user-h5/packageMall/pages/after-sale/after-sale.json`
- Create: `user-h5/packageMall/pages/after-sales/after-sales.js`
- Create: `user-h5/packageMall/pages/after-sales/after-sales.wxml`
- Create: `user-h5/packageMall/pages/after-sales/after-sales.wxss`
- Create: `user-h5/packageMall/pages/after-sales/after-sales.json`
- Create: `user-h5/packageMall/pages/after-sale-detail/after-sale-detail.js`
- Create: `user-h5/packageMall/pages/after-sale-detail/after-sale-detail.wxml`
- Create: `user-h5/packageMall/pages/after-sale-detail/after-sale-detail.wxss`
- Create: `user-h5/packageMall/pages/after-sale-detail/after-sale-detail.json`
- Modify: `user-h5/packageMall/pages/order-detail/order-detail.js`
- Modify: `user-h5/packageMall/pages/order-detail/order-detail.wxml`
- Modify: `user-h5/packageMall/pages/order-detail/order-detail.wxss`
- Modify: `user-h5/packageMall/pages/orders/orders.js`
- Modify: `user-h5/packageMall/pages/orders/orders.wxml`
- Modify: `user-h5/packageMall/utils/mall.js`
- Modify: `user-h5/utils/api.js`
- Modify: `user-h5/app.json`
- Modify: `user-h5/scripts/sync-lucide-icons.mjs`
- Modify: `user-h5/scripts/mall-pages.test.mjs`

**Interfaces:**

- Consumes: Task 2/3 app endpoints.
- Produces: apply page with item quantity selection, list page, detail page; order detail/list buttons `申请售后`, `售后记录`, and active-after-sale blocking.

- [ ] **Step 1: Write failing UI contract tests**

Extend `mall-pages.test.mjs` with:

```js
assert.ok(pageNames.includes('after-sale'));
assert.ok(pageNames.includes('after-sales'));
assert.ok(pageNames.includes('after-sale-detail'));
assert.match(orderDetailJs, /createAfterSale/);
assert.match(orderDetailWxml, /申请售后/);
assert.match(afterSaleJs, /maxQuantity|remainingQuantity/);
assert.match(afterSaleJs, /refundAmount/);
```

Also assert no `px`, no native `<button>`, token-only colors, and local Lucide paths.

- [ ] **Step 2: Run the failing UI test**

Run: `node scripts/mall-pages.test.mjs`
Expected: FAIL because pages/routes do not exist.

- [ ] **Step 3: Implement pages and navigation**

Apply page loads order detail and computes `remainingQuantity = order quantity - refundedQuantity` per item; posts only `orderItemId` and quantity. Do not send amount. List/detail pages show `PENDING/APPROVED/REFUNDING/SUCCESS/FAILED/REJECTED` and a retry affordance for `FAILED`. Add `canApplyAfterSale`/`hasAfterSale` to `mall.decorateOrder` for `SHIPPED/COMPLETED`, with active after-sale suppressing duplicate apply. Add all pages to `app.json` in `packageMall.pages`.

- [ ] **Step 4: Run the UI checks**

```powershell
cd E:\项目\milkTea\user-h5
npm run icons
npm run check
node scripts/mall-pages.test.mjs
node scripts/mall-cart.test.mjs
node scripts/check-project.mjs
```

Expected: all PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add user-h5/packageMall/pages/after-sale user-h5/packageMall/pages/after-sales user-h5/packageMall/pages/after-sale-detail user-h5/packageMall/pages/order-detail user-h5/packageMall/pages/orders user-h5/packageMall/utils/mall.js user-h5/utils/api.js user-h5/app.json user-h5/scripts/sync-lucide-icons.mjs user-h5/scripts/mall-pages.test.mjs user-h5/assets/icons/lucide
git commit -m "feat: add mall after-sale mini-program pages"
```

---

### Task 8: 管理端售后审核页

**Files:**

- Create: `src/views/mall/after-sale/index.vue`
- Modify: `src/service/api/mall.ts`
- Modify: `src/router/elegant/routes.ts`
- Modify: `src/router/elegant/imports.ts`
- Modify: `src/router/elegant/transform.ts`
- Modify: `src/typings/elegant-router.d.ts`
- Modify: `src/locales/langs/zh-cn.ts`
- Modify: `src/locales/langs/en-us.ts`
- Modify: `src/views/mall/mall-admin.structure.test.mjs`
- Modify: `server/src/main/resources/db/migration/V88__mall_after_sale.sql`

**Interfaces:**

- Consumes: Task 3 admin endpoints.
- Produces: list with status/order filters, detail modal, approve/reject actions, refund status display; `MallOrderStatus` includes `REFUNDED`.

- [ ] **Step 1: Write failing admin structure test**

```js
assert.match(source, /fetchMallAfterSales/);
assert.match(source, /approveMallAfterSale/);
assert.match(source, /rejectMallAfterSale/);
assert.match(source, /DetailModal/);
assert.match(source, /REFUNDED/);
```

- [ ] **Step 2: Run the failing admin test**

Run: `node --test src/views/mall/mall-admin.structure.test.mjs`
Expected: FAIL because the page/API is absent.

- [ ] **Step 3: Implement the admin page and route**

Use `AdminListPage`, `DetailModal`, `renderTag`, `formatFen`, `formatDateTime`. Add route `mall_after-sale` at `/mall/after-sale`, menu entry, i18n labels, and API functions. Add `REFUNDED` to `MallOrderStatus`, order status map/options, and display refund state. Add admin review action handlers that send only reviewer remark; approval amount stays server-side.

- [ ] **Step 4: Run the admin checks**

```powershell
node --test src/views/mall/mall-admin.structure.test.mjs
pnpm typecheck
pnpm build
```

Expected: PASS.

- [ ] **Step 5: Commit only this task**

```powershell
git add src/views/mall/after-sale/index.vue src/service/api/mall.ts src/router/elegant/routes.ts src/router/elegant/imports.ts src/router/elegant/transform.ts src/typings/elegant-router.d.ts src/locales/langs/zh-cn.ts src/locales/langs/en-us.ts src/views/mall/mall-admin.structure.test.mjs server/src/main/resources/db/migration/V88__mall_after_sale.sql
git commit -m "feat: add mall after-sale admin review"
```

---

### Task 9: 全链路回归与交付检查

**Files:**

- Modify: `docs/data-schema.md`
- Modify: `docs/调整方案-小程序商城.md` if the implementation contract differs from the current prose.
- Test: existing suites plus new after-sale tests.

**Interfaces:**

- Consumes: all prior tasks.
- Produces: verified build/test evidence; no unresolved placeholder or fake-success path.

- [ ] **Step 1: Run backend tests**

```powershell
mvn -q -pl trade-service,server -am test
mvn -q -pl product-service -am test
```

Expected: PASS. If an unrelated existing dirty-worktree test fails, record the exact failure and do not claim the after-sale suite passed.

- [ ] **Step 2: Run frontend checks**

```powershell
pnpm typecheck
pnpm build
```

Expected: PASS.

- [ ] **Step 3: Run mini-program checks**

```powershell
cd E:\项目\milkTea\user-h5
npm run check
node scripts/mall-pages.test.mjs
node scripts/mall-cart.test.mjs
node scripts/check-project.mjs
```

Expected: PASS.

- [ ] **Step 4: Review diff boundaries**

```powershell
git status --short
git diff --check
git diff --stat
```

Confirm only after-sale-related files changed by this task; unrelated dirty files remain untouched.

- [ ] **Step 5: Final verification**

Run `verification-before-completion`; only report completion after fresh command output confirms all required checks. Do not stage `.codex-logs/` or `deploy/release/`.
