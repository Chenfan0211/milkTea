# 订单今日过滤与储值订单操作优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 今日订单只展示真实属于今天的订单，并为储值订单补齐封面图、支付倒计时、取消订单与立即支付能力。

**Architecture:** 前端继续通过订单数据层聚合门店 / 储值 / 礼品卡订单，但“今日 / 历史”改为由订单创建时间推导，不再信任接口页签参数。储值封面图短期复用现有本地运营图；后端为储值订单补充取消接口，前端统一走后端状态刷新。

**Tech Stack:** 微信小程序原生页面、Node 断言脚本、Spring Boot / MyBatis-Plus / MySQL。

**Spec:** 需求来自用户提供的两张订单页截图；实现约束来自 `AGENTS.md`、`user-h5/docs/design-system.md`、图标与高清素材规范。

## Global Constraints

- 今日订单不得显示非今天创建的订单；“今日”按本地日历日判断。
- 储值未支付订单必须显示封面图、支付倒计时、“取消订单”和“立即支付”。
- 订单状态以后端为准；取消必须调用后端接口，不得只改本地数据。
- 小程序 UI 只使用设计系统 token；间距只取 `4 / 8 / 12 / 16 / 20 / 24 / 32 / 40rpx`；禁止原生 `<button>`。
- 可点击元素必须补 `aria-role="button"` 和语义化 `aria-label`。
- 图标与图片资源必须来自 `user-h5/assets/`；不得引用 `node_modules`、远程 CDN 或 `assets/temp`。
- 金额后端单位为分，前端展示为元。
- 改动后必须执行 `npm run check`，涉及页面 / 图标时还要通过 `node user-h5/scripts/check-project.mjs`。

---

## 现状判断

- `pages/orders/orders.wxml` 已有封面卡、倒计时和待支付按钮结构，但储值订单缺少 `coverImage`，且后端储值订单状态被归一为 `unpaid`，不会进入现有 `isPendingPayment` 分支，所以图二表现为灰块、无倒计时和按钮。
- `utils/orders.js` 目前把接口返回的每一批数据直接打上 `timeGroup=today/history`。当“全部订单”调用门店订单接口时，昨天订单也会被标成 `today`，这是图一串日期的根因。
- 储值后端目前只有建单、查单、分页和支付，没有用户取消接口；前端不能安全实现取消。

## Task 1: 修正今日 / 历史订单归属

**Files:**
- Modify: `user-h5/utils/orders.js`
- Test: `user-h5/scripts/orders-pending-payment.test.mjs` 或新增 `user-h5/scripts/orders-time-group.test.mjs`

**Interfaces:**
- Consumes: 订单 `createTime` / `orderInfo.createdAt`。
- Produces: `resolveTimeGroup(timeText, now)`；`refreshOrdersFromRemote()` 返回的订单 `timeGroup` 必须与创建时间一致。

- [ ] **Step 1: 写失败测试**

验证两个核心行为：

```js
// 昨天订单即使出现在“today”请求中，也不能标成 today
assert.equal(resolveTimeGroup('2026-09-26 23:59:59', new Date('2026-09-27 00:00:01').getTime()), 'history');
assert.equal(resolveTimeGroup('2026-09-27 00:00:01', new Date('2026-09-27 00:00:01').getTime()), 'today');

// refreshOrdersFromRemote 的测试夹具中包含昨天订单
// 请求 timeGroup=today 后，昨天订单的 timeGroup 必须是 history
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node user-h5/scripts/orders-time-group.test.mjs
```

预期：`resolveTimeGroup is not a function` 或断言失败。

- [ ] **Step 3: 实现本地日历日推导**

在 `utils/orders.js` 增加：

```js
function isSameLocalDay(left, right) {
  return left.getFullYear() === right.getFullYear() &&
    left.getMonth() === right.getMonth() &&
    left.getDate() === right.getDate();
}

function resolveTimeGroup(value, now = Date.now()) {
  const time = parseDateTime(value);
  if (!time) return 'history';
  return isSameLocalDay(time, new Date(now)) ? 'today' : 'history';
}
```

`refreshOrdersFromRemote()` 内不再直接把请求参数 `today` 写入每条订单；改为读取 `createTime` / `orderInfo.createdAt` 后调用 `resolveTimeGroup()`。分页刷新时用同一 category 清理旧数据，避免同一订单被不同 `timeGroup` 重复保留。

- [ ] **Step 4: 运行订单相关测试**

```bash
node user-h5/scripts/orders-pending-payment.test.mjs
node user-h5/scripts/orders-countdown.test.mjs
node user-h5/scripts/orders-time-group.test.mjs
```

预期：全部通过。

- [ ] **Step 5: 提交**

```bash
git add user-h5/utils/orders.js user-h5/scripts/orders-time-group.test.mjs
git commit -m "fix(orders): derive today group from order time"
```

## Task 2: 储值订单展示封面图和待支付态

**Files:**
- Modify: `user-h5/utils/orders.js`
- Modify: `user-h5/pages/orders/orders.wxss`
- Test: `user-h5/scripts/orders-pending-payment.test.mjs`

**Interfaces:**
- Consumes: `normalizeAuxOrder(order, 'stored-value')`。
- Produces: 储值订单装饰字段 `coverImage`、`isPendingPayment`、`countdownText`。

- [ ] **Step 1: 写失败测试**

```js
const stored = normalizeAuxOrder({
  id: 1,
  orderNo: 'CZ20260927001',
  amount: 20000,
  payStatus: 'UNPAID',
  createTime: '2026-09-27 17:00:00'
}, 'stored-value');

const decorated = orderStore.decorateOrder(stored, NOW);
assert.equal(decorated.coverImage, '/assets/images/3x/stored-value-banner.jpg');
assert.equal(decorated.isPendingPayment, true);
assert.ok(decorated.countdownText);
```

- [ ] **Step 2: 运行测试确认失败**

```bash
node user-h5/scripts/orders-pending-payment.test.mjs
```

预期：`coverImage` 为空或 `isPendingPayment` 为 false。

- [ ] **Step 3: 补齐展示字段**

- `normalizeAuxOrder()` 继续保留内部状态 `unpaid / paid / canceled`。
- `decorateOrder()` 对储值单追加展示态：

```js
const isStoredValueUnpaid = order.category === 'stored-value' && order.orderStatus === 'unpaid';
const isPendingPayment = (order.orderStatus === 'pending_payment' || isStoredValueUnpaid) && !isCanceled;
```

- 倒计时仍按 `createTime + PAYMENT_WINDOW_SECONDS` 推算。
- `tickOrderCountdowns()` 的刷新条件同步支持储值 `unpaid`。
- 封面兜底顺序：订单自带 `coverImage` > 储值运营图 `/assets/images/3x/stored-value-banner.jpg` > 空字符串。
- 为封面图补一个与现有卡片一致的最小高度兜底样式，避免图片路径异常时塌陷。

- [ ] **Step 4: 验证页面结构**

检查 `orders.wxml` 现有 `wx:else` 封面卡和 `isPendingPayment` 按钮块会自动覆盖储值订单。不得新增重复按钮块。

- [ ] **Step 5: 运行前端测试**

```bash
node user-h5/scripts/orders-pending-payment.test.mjs
node user-h5/scripts/orders-countdown.test.mjs
```

预期：全部通过。

- [ ] **Step 6: 提交**

```bash
git add user-h5/utils/orders.js user-h5/pages/orders/orders.wxss user-h5/scripts/orders-pending-payment.test.mjs
git commit -m "feat(orders): render stored value cover and pending state"
```

## Task 3: 后端提供储值订单取消接口

**Files:**
- Modify: `marketing-service/src/main/java/com/wuling/marketing/entity/StoredValueOrder.java`
- Modify: `marketing-service/src/main/java/com/wuling/marketing/service/StoredValueService.java`
- Modify: `marketing-service/src/main/java/com/wuling/marketing/controller/AppMarketingController.java`
- Test: `marketing-service/src/test/java/com/wuling/marketing/service/StoredValueOrderCancelTest.java`

**Interfaces:**
- Consumes: 当前登录用户 ID、储值订单号。
- Produces: `POST /api/v1/app/stored-value/orders/{orderNo}/cancel`；订单返回 `packageImage`、`status=CANCELED`。

- [ ] **Step 1: 写失败测试**

覆盖四类场景：

1. 本人取消 `UNPAID` 订单成功，数据库状态变为 `CANCELED`。
2. 非本人取消返回 NOT_FOUND，不泄露订单存在性。
3. 已支付订单不能取消。
4. 已取消订单幂等返回当前状态，不再重复变更。

- [ ] **Step 2: 运行测试确认失败**

```bash
mvn -pl marketing-service test -Dtest=StoredValueOrderCancelTest
```

预期：编译失败或断言失败。

- [ ] **Step 3: 实现取消服务**

`StoredValueService` 新增：

```java
@Transactional(rollbackFor = Exception.class)
public StoredValueOrder cancelOrder(Long userId, String orderNo) {
  StoredValueOrder order = requireByOrderNo(orderNo);
  if (!order.getUserId().equals(userId)) {
    throw new BusinessException(ResultCode.NOT_FOUND, "储值订单不存在");
  }
  if (PAY_CANCELED.equalsIgnoreCase(order.getPayStatus())) {
    return withDerivedStatus(order);
  }
  if (PAY_PAID.equalsIgnoreCase(order.getPayStatus())) {
    throw new BusinessException(ResultCode.BAD_REQUEST, "已支付订单不能取消");
  }

  StoredValueOrder patch = new StoredValueOrder();
  patch.setId(order.getId());
  patch.setPayStatus(PAY_CANCELED);
  int affected = orderMapper.update(patch, new LambdaQueryWrapper<StoredValueOrder>()
      .eq(StoredValueOrder::getId, order.getId())
      .eq(StoredValueOrder::getPayStatus, PAY_UNPAID));
  if (affected == 0) {
    return requireByOrderNo(orderNo);
  }
  return withDerivedStatus(requireByOrderNo(orderNo));
}
```

状态推导补充 `CANCELED -> CANCELED`；`withDerivedStatus()` 同时填充 `packageImage`。

- [ ] **Step 4: 暴露接口和展示字段**

- `StoredValueOrder` 增加非库字段 `packageImage`。
- `AppMarketingController` 新增：

```java
@PostMapping("/stored-value/orders/{orderNo}/cancel")
public Result<StoredValueOrder> cancelStoredValueOrder(@PathVariable String orderNo) {
  return Result.ok(storedValueService.cancelOrder(CurrentUser.require(), orderNo));
}
```

- 短期 `packageImage` 固定返回 `/assets/images/3x/stored-value-banner.jpg`；后续若要每个套餐独立图，再新增数据库字段和后台配置。
- 已取消订单的 `status` 必须下发 `CANCELED`，不能继续按 `CREATED` 返回。

- [ ] **Step 5: 运行后端测试**

```bash
mvn -pl marketing-service test -Dtest=StoredValueOrderCancelTest
```

预期：通过。

- [ ] **Step 6: 提交**

```bash
git add marketing-service/src/main/java/com/wuling/marketing marketing-service/src/test/java/com/wuling/marketing/service/StoredValueOrderCancelTest.java
git commit -m "feat(marketing): cancel unpaid stored value orders"
```

## Task 4: 前端接入取消和立即支付

**Files:**
- Modify: `user-h5/utils/api.js`
- Modify: `user-h5/utils/orders.js`
- Modify: `user-h5/pages/orders/orders.js`
- Test: `user-h5/scripts/orders-pending-payment.test.mjs`

**Interfaces:**
- Consumes: 后端取消接口、`api.prepayStoredValue(orderNo)`、`api.fetchStoredValueOrder(orderNo)`。
- Produces: 页面方法 `payOrder(event)`；`cancelOrder(event)` 可取消储值订单。

- [ ] **Step 1: 写失败测试**

验证：

```js
assert.ok(apiJs.includes("cancelStoredValueOrder"));
assert.ok(ordersWxml.includes('catchtap="payOrder"'));
assert.ok(ordersPageJs.includes('payOrder(event)'));
```

并用 mock `wx.requestPayment` 验证支付成功后会调用 `fetchStoredValueOrder` 刷新订单列表。

- [ ] **Step 2: 运行测试确认失败**

```bash
node user-h5/scripts/orders-pending-payment.test.mjs
```

预期：缺少取消 API / 支付方法相关断言失败。

- [ ] **Step 3: 接入 API 和取消分流**

`api.js` 增加：

```js
function cancelStoredValueOrder(orderNo) {
  return request({
    url: `/api/v1/app/stored-value/orders/${encodeURIComponent(orderNo)}/cancel`,
    method: 'POST'
  }).then(unwrap);
}
```

`utils/orders.js` 新增 `cancelStoredValueOrderById(id)`：

1. 通过订单号调用 `api.cancelStoredValueOrder()`。
2. 成功后按当前页签 / 分类刷新远端列表。
3. 返回刷新后的订单。

`cancelOrderById(id)` 按 `order.category === 'stored-value'` 分流，页面现有“取消订单”按钮不需要重复绑定。

- [ ] **Step 4: 接入立即支付**

`pages/orders/orders.js` 新增 `payOrder(event)`：

1. 读取 `data-id`，解析订单号。
2. `wx.showLoading({ title: '正在支付', mask: true })`。
3. 调用 `api.prepayStoredValue(orderNo)`。
4. `result.params` 存在时调用 `wx.requestPayment`。
5. 支付成功或 mock 环境无参数时轮询 `api.fetchStoredValueOrder(orderNo)`。
6. 以 `payStatus === 'PAID'` 为准，最后 `loadOrders()` 刷新列表。
7. 用户取消支付只提示“支付未完成”，不改本地订单状态。

- [ ] **Step 5: 运行前端测试**

```bash
node user-h5/scripts/orders-pending-payment.test.mjs
node user-h5/scripts/orders-countdown.test.mjs
```

预期：全部通过。

- [ ] **Step 6: 提交**

```bash
git add user-h5/utils/api.js user-h5/utils/orders.js user-h5/pages/orders/orders.js user-h5/scripts/orders-pending-payment.test.mjs
git commit -m "feat(orders): add stored value pay and cancel actions"
```

## Task 5: 回归验证与验收

**Files:**
- No new production files.

- [ ] **Step 1: 检查 UI 规范**

确认订单页间距、颜色、字号、圆角均使用 token；无新增图标时不需要运行 `npm run icons`。

- [ ] **Step 2: 执行小程序全量检查**

在 `user-h5` 目录执行：

```bash
npm run check
```

预期：命令退出码为 0。

- [ ] **Step 3: 执行后端相关测试**

在仓库根目录执行：

```bash
mvn -pl marketing-service test -Dtest=StoredValueOrderCancelTest
```

预期：通过。

- [ ] **Step 4: 手工验收**

1. 使用今天订单账号进入“今日订单 > 全部订单”，确认昨天订单不出现在今日列表，历史订单仍可在“历史订单”查看。
2. 进入“储值订单”，未支付订单显示储值图片、`mm:ss` 倒计时、“取消订单”和“立即支付”。
3. 点击取消并确认后，订单变为已取消；刷新后仍为已取消。
4. 点击立即支付后完成 / 取消支付，页面状态以后端查单结果为准。
5. 已支付储值订单不显示取消和立即支付按钮。

- [ ] **Step 5: 记录结果**

在执行记录中粘贴关键命令输出与退出码；没有通过前不得宣告完成。
