# 运营后台字段中文化 + 日期格式 + 字段补齐 调整计划

> 创建时间：2026-09-25
> 状态：✅ 已全部落地（2026-09-25）
> 目标：消除运营后台所有英文字段/枚举残留，统一日期格式为 `yyyy-MM-dd HH:mm:ss`，对比小程序端补齐遗漏字段。
> 策略（用户确认）：**后端统一枚举** + **前端对齐** + **日期统一** + **字段补齐**（完整范围）。

---

## 一、问题根因

后端各表 `status`/`type`/`issue_type` 枚举值存在「实体常量（权威）」「seed 脏数据」「前端映射」三套不一致，导致前端 `renderTag` 兜底失败时原样显示英文。

关键证据：
- `finance/entity/ReconcileIssue.java` 常量：`MISSING_SPLIT` / `MISSING_REVERSE` / `SPLIT_AMOUNT_MISMATCH`
- `V22__*.sql` seed 写入：`AMOUNT_MISMATCH` / `MISSING_PAYMENT` / `MISSING_ORDER` / `STATUS_MISMATCH` / `DUPLICATE_PAY`，且 `status` 混入 `PROCESSING`
- `VerifyService.java`：`verify_record.type` 写入 `ORDER`/`EXCHANGE`（大写），`result` 写入 `success`/`rejected`（小写）
- `LedgerService.java`：`fund_flow.type` 写入 `INCOME`/`SETTLE`/`FREEZE`/`UNFREEZE`/`WITHDRAW`/`REFUND`
- `GiftCardService.java:99`：礼品卡订单 `status` 有 `CANCELED`

---

## 二、问题清单（全部）

### A. 英文枚举残留（10 处）

| # | 文件 | 字段 | 问题 | 修复 |
|---|------|------|------|------|
| A1 | finance/reconcile/index.vue | issueType | statusMap 用中文 key，DB 值是英文 | 改英文 key：`MISSING_SPLIT`/`MISSING_REVERSE`/`SPLIT_AMOUNT_MISMATCH` |
| A2 | finance/reconcile/index.vue | status | 缺 `PROCESSING` | 后端迁移归并；前端保留兜底 |
| A3 | trade/order + order-detail + home | status | 缺 `CANCELED` | 补 `CANCELED:已取消` |
| A4 | finance/withdraw/index.vue | status | 缺 `AUDITING`/`FAILED`；rowActions 用 `pending` 小写判断 | 补映射 + 判断改 `APPLIED` |
| A5 | trade/payment/index.vue | thirdStatus | 缺 `FAIL`/`PROCESSING`/`DUPLICATE_PAY` | 补映射 |
| A6 | finance/flow/index.vue | type | 臆造 `ADJUST`，缺 `SETTLE` | 补 `SETTLE`，移除 `ADJUST` |
| A7 | trade/verify/index.vue | type | statusMap 用中文 key（订单/兑换），DB 值 `ORDER`/`EXCHANGE` | 改英文 key |
| A8 | trade/verify-detail/index.vue | type | 直接 `key:'type'` 展示英文 | 补 `typeLabel` render |
| A9 | marketing/gift-order/index.vue | status | 缺 `CANCELED` | 补 `CANCELED:已取消` |
| A10 | finance/snapshot-detail/index.vue | totalCheck | 直接 `key:'totalCheck'`，值可能英文 | 补 label 映射（一致/不一致） |

### B. 日期格式统一（全局）

| # | 位置 | 修复 |
|---|------|------|
| B1 | `_shared/render.ts` | 新增 `formatDateTime`/`formatDate` 工具（对齐小程序 `date-format.js`） |
| B2 | store/modules/admin/index.ts `now()`/`daysAgo()` | 补秒 |
| B3 | 各列表页时间列 | 统一加 `render: formatDateTime`（约 25 处） |
| B4 | home/index.vue `slice(11)` | 改 `formatDateTime` |

### C. 字段补齐（对比小程序）

| # | 文件 | 补字段 |
|---|------|--------|
| C1 | trade/order-detail/index.vue | 商品明细 items、支付方式 payMethod、couponDiscount/originalAmount/discountAmount、completeTime |
| C2 | trade/order/index.vue | payStatus（支付状态）、mealType（用餐方式）列 |
| C3 | user/list/index.vue | phone（手机号）、createTime（注册时间） |

---

## 三、执行顺序

1. **后端**：新增 `V33__unify_enum_values.sql` 迁移脚本，统一 issue_type/status 存量数据。
2. **前端共享层**：`_shared/render.ts` 补兜底字典 + 日期工具。
3. **前端逐页**：A1~A10 枚举映射修正。
4. **日期**：B1~B4。
5. **字段**：C1~C3。
6. **验证**：`mvn test` + 前端 `npm run check`/`npm run lint`。

---

## 四、验证标准

- 后端：`MigrationFilesTest` 通过；`fund_flow.type`、`reconcile_issue.issue_type/status`、`verify_record.type` 取值与前端映射一一对应。
- 前端：各列表页状态列/时间列/下拉筛选无英文残留；订单详情页字段完整。
- 回归：小程序 `user-h5` 不受影响。


---

## 补充：订单明细后端支持（2026-09-25 追加）

用户要求"需要改后端"支持订单详情的商品明细 items。改动如下：

### 后端
1. `trade-service/.../controller/AdminOrderController.java`：新增 `GET /api/v1/admin/trade/orders/{orderNo}` 详情接口（调用 `getByOrderNo`，返回含 items 的完整 OrderDTO）。
2. `trade-service/.../port/ProductQueryPort.java`：补 `ProductView.image` 字段声明（历史遗留半成品，getter/setter 已存在但缺字段，导致模块编译失败；修复后 trade-service 恢复可编译）。

### 前端
1. `src/service/api/trade.ts`：`fetchAdminOrders`/`fetchAdminOrderDetail` 加 `unwrap` 解包并改为 async。
2. `src/store/modules/admin/index.ts`：新增 `loadAdminOrders`/`loadAdminOrderDetail`，订单列表走专用接口（返回完整 OrderDTO）。
3. `src/views/trade/order/index.vue`：`loadData` 改走 `loadAdminOrders`；详情跳转改传 `orderNo`。
4. `src/views/trade/order-detail/index.vue`：改为异步 `fetchRow`（走详情接口），补商品明细/支付时间/核销时间/完成时间/金额明细字段。
5. `src/views/_shared/AdminDetailPage.vue`：`fetchRow` 支持返回 Promise（`await`）。

### 迁移脚本版本号
- 枚举统一脚本由 V33 改为 **V34**（V33 已被既有的 `V33__backfill_order_items_for_all_orders.sql` 占用）。

### 验证
- ✅ `vue-tsc --noEmit` 0 错误
- ✅ `mvn -pl trade-service -am compile` 编译通过（修复 image 字段后）

## 追加：订单明细商品图片（2026-09-25）

用户要求继续补订单明细商品图片。采用「订单快照」方案（下单时把商品图快照进 order_item），避免列表页 N+1 跨服务查询。

### 后端
1. `trade-service/entity/OrderItem.java`：新增 `image` 字段。
2. `trade-service/service/OrderService.java`：`createOrder` 里 `item.setImage(product.getImage())`（下单快照）；`toDTO` 里 `dtoItem.setImage(item.getImage())`（用快照，**替代原 N+1 的 findProduct 实时查询**）。
3. `server/db/migration/V35__order_item_image_snapshot.sql`：order_item 加 `image` 列 + 按 product_id 关联 product 表回填历史订单图片。

### 前端
1. `src/views/trade/order-detail/index.vue`：商品明细用 `h(NImage)` 渲染商品图 + 名称 x数量 + 规格 + 单价（无图时回落占位）。

### 验证
- ✅ `vue-tsc --noEmit` 0 错误
- ✅ oxlint（改动文件）0 错误 0 警告
- ✅ `mvn -pl trade-service -am compile` 编译通过

## 追加：小程序订单列表「堂食/自取」标签空白修复（2026-09-25）

用户反馈：小程序订单列表卡片左上角（门店名左侧）没有显示「堂食」还是「自取」。

### 根因
- 列表 wxml 用 `{{item.type}}` 渲染该标签，但 `utils/orders.js` 的 `decorateOrder` **从未给 `item.type` 赋值**，故标签位置为空白。
- 同时发现 `order-detail.wxml` 引用的 `order.mealInfo`（详情页「用餐信息」）也从未赋值，同样空白。
- 数据源：下单时 `mealType` 传 `'dinein'`（店内就餐）/ `'pickup'`（打包外带），后端 `orders.meal_type` 原样存储并经 `OrderDTO.mealType` 下发；seed 演示数据写入的是大写 `'TAKEOUT'`（两种大小写并存）。

### 修复
1. `user-h5/utils/orders.js`：
   - 新增 `MEAL_TYPE_TEXT` 映射（兼容 `dinein/pickup` 小写与 `DINE_IN/TAKEOUT` 大写、以及中文）。
   - 新增 `resolveMealTypeText(order)`：归一化为「堂食」/「自取」。
   - 新增 `buildMealInfo(order)`：组装详情页「用餐信息」（用餐方式 + 取餐门店）。
   - `decorateOrder` 返回值补充 `type` 与 `mealInfo` 字段（列表与详情共用同一装饰逻辑）。
2. `user-h5/pages/orders/orders.wxml`：标签加 `wx:if="{{item.type}}"`，历史订单无 `meal_type` 时不渲染空标签框。
3. `src/views/trade/order/index.vue`、`src/views/trade/order-detail/index.vue`（运营后台）：修正 `mealType` 映射（原误用大写 `TAKEOUT/DINE_IN`，与实际存储的小写 `dinein/pickup` 不匹配），口径与小程序统一为「堂食」/「自取」。

### 口径统一
- 首页「店内堂食 / 打包自取」、菜单页「堂食」→ 列表标签统一用简洁的 **堂食 / 自取**。

### 验证
- ✅ `node --check user-h5/utils/orders.js`
- ✅ `user-h5 npm run check`（含订单分类、倒计时、详情页取数等全部用例）
- ✅ `vue-tsc --noEmit` 0 错误

## 追加：小程序「取消订单」未调用后端接口修复（2026-09-25）

用户反馈：点击订单卡片上的「取消订单」按钮没有调用后端接口。

### 根因
- `user-h5/utils/orders.js` 的 `cancelOrderById` / `cancelPaidOrderById` **只在本地的 `orderStore` 镜像上改状态**，完全没调后端；
- 后端 `AppOrderController` **也没有取消订单接口**（仅有 create / list / detail / pay / callback）。
- 后果：界面上显示「已取消」，但后端订单仍是待支付/待核销，下拉刷新或换设备后又变回原状态，且已支付订单不会真正退款。

### 修复

**后端（trade-service）**
1. `OrderService.cancelUnpaid(orderNo, userId)`：行锁 + **订单归属校验** + 状态校验（仅 CREATED+UNPAID），置 CANCELED；已取消时幂等返回。
2. `AppOrderController` 新增 `POST /api/v1/app/orders/{orderNo}/cancel`：
   - 校验订单归属（JWT userId）；
   - `CREATED` → `cancelUnpaid`（直接关闭，无退款）；
   - `PAID` → `refundService.refund`（整单退款、原路退回、台账冲正）；
   - 其他状态明确报错。
   - 网关 `trade-app` 路由 `/api/v1/app/orders/**` 已覆盖；鉴权沿用 `PROTECTED_PREFIXES`（需登录），无需改网关配置。

**前端（user-h5）**
3. `utils/api.js`：新增 `cancelOrder(orderNo)` → `POST /api/v1/app/orders/{orderNo}/cancel` 并导出。
4. `utils/orders.js`：`cancelOrderById` / `cancelPaidOrderById` 改为**异步**——先经 `resolveOrderNo` 解析订单号，调后端取消接口，成功后 `refreshOrdersFromRemote` 重新拉取，保证本地镜像与后端一致。
5. `pages/orders/orders.js`、`pages/order-detail/order-detail.js`：取消 handler 改为异步，补 `wx.showLoading` / 失败 toast，避免把 Promise 误当订单对象 setData。

**测试**
6. `scripts/orders-pending-payment.test.mjs`：wx mock 补 `showLoading`/`hideLoading`/`request`；新增断言「取消必须发出 `POST .../cancel` 请求且携带正确订单号」；取消相关断言改为等待异步链完成。

### 验证
- ✅ `mvn -pl trade-service -am compile` 通过
- ✅ `node --check` 四个改动 JS 文件通过
- ✅ `user-h5 npm run test:orders` / `npm run check` 全部通过（含「取消必须调用后端取消接口」新断言）