# 储值卡多套餐选择 · 调整方案

> 创建日期：2026-09-23
> 涉及：`user-h5/pages/stored-value`（选择与充值）、`marketing-service`（套餐查询）、`server`（后台 CRUD）

## 一、背景与需求

需求：储值卡充值走后台接口；后台可设置每张储值卡的金额，也可配置多张储值卡；
小程序端需要**展示多张储值卡供用户选择**。

## 二、现状排查：数据链条已通，卡在页面渲染

排查结论：**后端与后台管理端都已完成多套餐支持，只有小程序端没接。**

| 环节 | 位置 | 现状 |
| --- | --- | --- |
| 表结构 | `stored_value_package`（V2） | ✅ 支持多套餐，`code` 唯一 |
| 赠券关联 | `stored_value_package_coupon`（V2） | ✅ 已建表（`package_id` + `coupon_id` + `count`） |
| 初始数据 | `V6__seed_marketing.sql` | ✅ 已 seed **3 个套餐**：100 / 200 / 500 元 |
| 查询接口 | `GET /api/v1/app/stored-value/packages` | ✅ 返回 `List<StoredValuePackage>`（多套餐） |
| 充值接口 | `POST /api/v1/app/stored-value/recharge?packageId=` | ✅ 按 `packageId` 充值 |
| 后台 CRUD | `CrudRegistry` → `storedValuePackages` | ✅ 可增删改金额、状态 |
| 后台页面 | `src/views/marketing/stored/index.vue` | ✅ 可编辑金额 / 赠券 / 使用说明 |
| 前端 API | `user-h5/utils/api.js#fetchStoredValuePackages` | ✅ 已封装 |
| **小程序页面** | `pages/stored-value` | ❌ **单卡渲染，未做选择** |

### 根因（硬证据）

**根因 1：接口拿到了数组，页面却只渲染一张卡。**

- `stored-value.js` 第 36–38 行把接口结果写进了 `packages`：
  ```js
  .then(list => { if (Array.isArray(list) && list.length) this.setData({ packages: list }); })
  ```
- 但 WXML **从未消费 `packages`**。`grep wx:for` 只命中 `giftItems` 与 `usageParagraphs`。
- `stored-value.wxml` 第 43–44 行渲染的是 `{{storedValuePackage.amount}}` ——
  而 `storedValuePackage` **在 `data` 里根本没声明**（`data` 只有 `packages` 与之无关的字段），
  恒为 `undefined`，所以金额区空着。

**根因 2：充值按钮是假按钮。**

- `doHandleRecharge()` 直接 `showUnavailable('储值支付')`，**从未调用** `api.rechargeStoredValue()`。
- 即：即便用户选了卡，也无法真正充值。

**根因 3：赠券/说明的数据形状对不上。**

- `StoredValuePackage` 实体只有 `id / code / name / amount / status`，
  **不含 `coupons` 与 `usageParagraphs`**。
- 而 `utils/stored-value.js#buildStoredValueSummary` 期望
  `package.coupons[]`（含 `amount / quantity / description`）。
- 后台 Vue 页把 `coupons`、`usageParagraphs` 当作套餐字段在编辑，
  但**落库位置**是 `stored_value_package_coupon` 关联表，二者未在接口层打通。
- 结论：赠券需要**新接口或 DTO 聚合**才能下发到小程序。

## 三、目标

1. 小程序储值页展示**后台配置的全部启用套餐**，用户可点选其一。
2. 选中的套餐驱动下方「赠送优惠」与「立即储值 ¥X」金额。
3. 点「立即储值」真实调用充值接口并完成入账。
4. 套餐的**赠券内容与使用说明由后台下发**，不在前端写死。
5. 保持后台「多套餐 + 自定义金额」能力不变（已具备，无需重做）。

## 四、方案

### 第 1 层：后端补齐套餐聚合 DTO（关键前置）

新增 `StoredValuePackageDTO`，把关联表数据聚合进来：

```
StoredValuePackageDTO {
  id, code, name, amount, status,
  coupons: [ { couponId, amount, quantity, description } ],   // 来自关联表 + coupon 表
  usageParagraphs: [ ... ]                                     // 见下
}
```

- `GET /api/v1/app/stored-value/packages` 改为返回 `List<StoredValuePackageDTO>`。
- 赠券：`stored_value_package_coupon` JOIN `coupon`，数量按 `count` 下发。
- **使用说明**：现有 schema 无存放位置，二选一（见下方待确认项）：
  - 方案 A：新增 `usage_paragraphs` JSON 列到 `stored_value_package`（加迁移 `V21`）；
  - 方案 B：存 `app_config`（如 `stored_value_usage`），全局共用一份说明。
- 保持**向后兼容**：旧字段 `id/code/name/amount/status` 不变，仅新增两个字段。

### 第 2 层：小程序改多卡选择

- 新增 `selectedPackageId` 到 `data`，默认选中第一个（或中间价位的推荐档）。
- `stored-value.wxml` 的 `package-card` 改为 `wx:for="{{packages}}"` 循环，
  每张卡按 `selectedPackageId === item.id` 切换选中态（品牌绿描边 + 浅底）。
- `updateSummary()` 改用**选中套餐**计算总价与赠券，而非空的 `storedValuePackage`。
- 选中态补 `aria-role="button"` + `aria-label="选择{{item.amount}}元储值卡"`。
- 「立即储值 ¥X」金额随选中套餐实时变化。

### 第 3 层：打通真实充值

- `doHandleRecharge()` 改为：
  ```
  api.rechargeStoredValue(selectedPackageId)
    → 成功后刷新余额（user-profile）+ Toast 提示 + 可选跳转余额记录
  ```
- 未绑手机号仍走既有 `loginGuard.requirePhone` 前置校验。
- 失败态：Toast 提示后端 message，不吞异常。

### 第 4 层：清理与规范

- 补回 `storedValuePackage` 相关死引用（改为选中套餐变量），删除恒空的占位渲染。
- 数值单位：后端金额为**分**，前端展示为**元**（既有约定，勿改）。
- 遵循 `user-h5/docs/design-system.md`：间距只取八档、颜色只取 token、
  图标走 Lucide、可点元素补 `aria-*`。

## 五、待确认项

1. **使用说明存放位置**：加列（`usage_paragraphs`）还是走 `app_config` 全局共享？
2. **选中默认值**：默认选第一个套餐，还是标记某个为「推荐」默认选中？
3. **份数步进器去留**：现有多份累加逻辑（1–10 份）是保留，还是简化为「一张卡一笔充值」？
4. **支付方式**：当前 `recharge()` 是 Mock 直接置 PAID 并入账（无真实支付）。
   本次是否只需打通这条 Mock 链路，还是要接微信支付？

## 六、验收标准

```powershell
cd user-h5
npm run check
npm run test:acceptance
```

```powershell
mvn -o -pl marketing-service,server -am compile
```

人工验收：

1. 后台新增/修改一个套餐金额并启用 → 小程序**重启后**出现该套餐。
2. 后台**禁用**某套餐 → 小程序不再展示。
3. 小程序展示 N 张卡，点选切换 → 选中态、「赠送优惠」、「立即储值 ¥X」三者同步。
4. 点「立即储值」→ 接口返回成功，账户余额增加对应金额。
5. 关掉后端 → 页面不白屏，给出可读的失败提示。
6. Console 无新增异常。

## 七、不做的事

- 不重做后台 CRUD（已具备「多套餐 + 自定义金额」能力）。
- 不改 `stored_value_package` 既有字段语义与金额「分」单位。
- 不在小程序端写死套餐金额或赠券文案。
- 不引入第三方 UI 组件库。

---

## 八、补充：微信支付接入的架构影响（2026-09-23 追加）

用户确认「**使用说明加列（每套餐独立）**」与「**一并接微信支付**」。
补充排查后，发现一个**决定性问题**，必须先讲清楚。

### 8.1 微信支付后端已完成，但**只服务「订单」**

项目里微信支付其实**已经全部实现**（`docs/wechat-pay-integration.md` 记载「后端代码已全部写完」）：

| 组件 | 位置 |
| --- | --- |
| `PaymentGateway` / `MockPaymentGateway` / `WechatPayGateway` | `trade-service/.../service/` |
| `PaymentGatewayResolver`（按 `app.pay.channel` 选型，**fail-fast**） | 同上 |
| `WxPayNotifyService`（验签 + AES-GCM 解密 + 防重放） | `trade-service/.../pay/wxpay/` |
| `WxPayNotifyController`（`POST /api/v1/app/payments/wxpay/notify`） | 同上 |
| `WxPayProperties` / `WxPaySdkConfig` / `WxPayTransaction` | 同上 |

**但它的入账路径与「订单」强绑定**：

```java
public OrderDTO handleWxPayCallback(WxPayTransaction transaction) {
    String orderNo = transaction.getOutTradeNo();
    Order order = orderService.lockForUpdate(orderNo);   // ← 只认订单表
    ...
    return settlePaid(orderNo, ...);                     // ← 只结算订单
}
```

### 8.2 决定性缺口：支付单没有「业务类型」字段

`Payment` 实体字段为：
`id / paymentNo / orderId / orderNo / amount / channel / thirdStatus / standardStatus / transactionId / callbackTime / prepayId / payerOpenid`

**没有 `bizType`（业务类型）字段。**

而储值充值的单据是 `stored_value_order`（在 `marketing-service`），
与 `trade-service` 的 `order` 表是**两套完全独立的单据模型**。

后果：微信回调回来时，`handleWxPayCallback` 只能去 `order` 表找 `outTradeNo`。
**储值订单的 `orderNo`（`CZ` 前缀）在订单表里根本不存在** →
回调找不到单据 → 余额永远不会入账。

**这意味着：储值走微信支付，不是「复用一下」，而是要新增一条独立的支付链路。**

### 8.3 修正后的工作量（显著高于原估计）

原方案第 3 层（Mock 充值）约 **0.5 天**；改走微信支付后，新增：

| # | 工作项 | 说明 |
| --- | --- | --- |
| 1 | `payment` 表加 `biz_type` | 区分 `ORDER` / `STORED_VALUE`（迁移 `V21`） |
| 2 | 回调按 `bizType` 路由 | `handleWxPayCallback` 需分流到储值入账服务 |
| 3 | 跨服务调用 | `trade-service` → `marketing-service` 内部接口（储值订单在 marketing） |
| 4 | 储值侧预下单 | 新增 `prepayForMiniApp` 等价的储值版本（openid 必须服务端取） |
| 5 | 幂等与金额校验 | 储值侧同样要行锁 + 金额比对 + `alertChannel` 告警 |
| 6 | 小程序端 | `wx.requestPayment` + 结果以后端回调为准 + 主动查单 |
| 7 | 退款链路 | 微信退款原路退回，需 `payerOpenid`（已具备） |
| 8 | 资质与配置 | 商户号 / APIv3 密钥 / 证书 / 回调域名（备案已过，需确认证书就绪） |

### 8.4 建议的落地顺序（降风险）

**不建议一步到位**。分两阶段：

**阶段一：多卡选择 + Mock 充值（先跑通业务闭环）**
- 完成第 1–4 层（套餐 DTO、多卡选择、Mock 充值、清理）。
- 价值：立刻能验证「后台配→小程序选→余额到账」整条业务链，且不依赖商户资质。

**阶段二：切换为微信支付**
- 在阶段一之上新增 8.3 的 1–6 项，复用既有 `WechatPayGateway` 与验签能力。
- 通过 `app.pay.channel` 切换，Mock 通道保留给本地开发与 CI。

**理由**：阶段一可独立验收、可回滚；阶段二涉及资金链路 + 跨服务 + 回调域名，
一旦出问题影响面大。先让业务逻辑正确，再让资金通道真实。

---

## 九、执行记录（2026-09-23）

用户确认「使用说明加列，每套餐独立」+「一次做完两阶段（含微信支付）」。

### 后端改动

| 文件 | 改动 |
| --- | --- |
| `V23__stored_value_usage_paragraphs.sql` | 新增 `stored_value_package.usage_paragraphs` JSON 列；为既有 3 个套餐补默认说明（仅在为空时写入，不覆盖运营已改内容） |
| `V24__payment_biz_type.sql` | `payment` 加 `biz_type` / `biz_no` + 路由索引；`order_id` / `order_no` 放宽为可空；`stored_value_order` 加 `transaction_id` / `payer_openid`；历史数据回填 `biz_type='ORDER'` |
| `marketing-service/.../dto/StoredValuePackageDTO.java` | 新增：聚合赠券明细与使用说明 |
| `marketing-service/.../entity/StoredValuePackageCoupon.java` + Mapper | 新增：补齐关联表实体（此前表存在但无实体，导致赠券无法下发） |
| `marketing-service/.../entity/StoredValuePackage.java` | 加 `usageParagraphs` |
| `marketing-service/.../entity/StoredValueOrder.java` | 加 `transactionId` / `payerOpenid` |
| `marketing-service/.../service/StoredValueService.java` | 重写：`listPackages()` 返回 DTO；拆出 `createOrder()`（建单 UNPAID）与 `markPaid()`（回调入账，条件更新做幂等闸门 + 金额比对）；新增 `orderView()` 供前端查单 |
| `marketing-service/.../internal/StoredValueInternalController.java` | 新增 `/internal/stored-value-orders/**`：供 trade 域反查与驱动入账 |
| `marketing-service/.../controller/AppMarketingController.java` | `/stored-value/recharge` → `/stored-value/orders`（建单）+ `/stored-value/orders/{orderNo}`（查单） |
| `trade-service/.../pay/storedvalue/StoredValueOrderPort.java` + `RemoteStoredValueOrderAdapter.java` | 新增：trade → marketing 的储值订单端口（失败抛异常，不吞） |
| `trade-service/.../pay/storedvalue/StoredValuePayController.java` | 新增 `POST /api/v1/app/payments/stored-value/prepay`：服务端取 openid、校验订单归属与金额 |
| `trade-service/.../service/PaymentService.java` | 新增 `routeWxPayCallback()` 按单号前缀路由（**核心修复**）、`handleStoredValueCallback()`、`prepayStoredValue()` |
| `trade-service/.../pay/wxpay/WxPayNotifyController.java` | 回调改走 `routeWxPayCallback()` |
| `trade-service/.../entity/Payment.java` | 加 `bizType` / `bizNo` 字段与常量 |
| `trade-service/.../port/RemoteProductQueryAdapter.java` | 新增 `marketingInternalRestClient` bean |
| `gateway/.../GatewayAuthPolicy.java` | 受保护清单补 `/stored-value/orders/**` 与 `/payments/stored-value/prepay`；移除已废弃的 `/recharge` |
| `server/.../config/WebConfig.java` | 同步移除 `/recharge`，改为 `/orders/**` |

### 前端改动

| 文件 | 改动 |
| --- | --- |
| `pages/stored-value/stored-value.js` | 重写：拉取套餐 → 默认选中中位档 → 选中态驱动摘要；充值改为「建单 → 发起支付 → 轮询查单」，以**服务端状态**为准 |
| `pages/stored-value/stored-value.wxml` | `package-card` 改为 `wx:for` 多卡渲染 + 选中态 + 选中角标；新增空态；步进器独立成行 |
| `pages/stored-value/stored-value.wxss` | 新增 `.package-list` / `.is-selected` / `.package-empty` / `.quantity-row`；全部使用设计 token |
| `utils/stored-value.js` | 新增 `normalizePackage()`（分→元换算 + 卡面副标题）、`pickDefaultPackageIndex()`（默认中位档） |
| `utils/api.js` | `rechargeStoredValue` → `createStoredValueOrder` / `fetchStoredValueOrder` / `prepayStoredValue` |
| `scripts/sync-lucide-icons.mjs` | 新增 `check-brand` 图标映射 |

### 新增测试

- `trade-service/.../StoredValueCallbackRoutingTest.java`：**守住资损级 bug** ——
  验证 CZ 前缀被正确识别、点单单号不被误判、null 安全、前缀须锚定开头。
- `scripts/stored-value-page.test.mjs` 扩展：归一化换算、默认选中中位档、
  选中切换驱动摘要、重复点选不重算、越界下标忽略、`check-brand` 图标存在。

两个测试均做过**反向验证**（注入回归后立即失败），确认非空跑。

### 验证结果

```text
npm run check              # 通过（23 个测试脚本）
npm run test:acceptance    # 通过
npm run icons              # 114 个 SVG 同步完成
mvn -o -pl trade-service,marketing-service,gateway,server test
                           # 43 个测试全通过，BUILD SUCCESS
mvn compile（全模块）       # EXIT=0
```

### 待人工完成

1. **执行迁移**：本机无 MySQL/Docker，V23/V24 未实际执行。
   部署时由 Flyway 自动运行；SQL 已通过 AST 解析校验，
   且 `PREPARE/EXECUTE` 模式与已在生产跑过的 V22 完全一致。
2. **注入微信支付配置**（环境变量，不进仓库）：
   `WXPAY_MCH_ID` / `WXPAY_APP_ID` / `WXPAY_API_V3_KEY` /
   `WXPAY_MCH_SERIAL_NO` / `WXPAY_PRIVATE_KEY_PATH` /
   `WXPAY_PUBLIC_KEY_ID` / `WXPAY_PUBLIC_KEY_PATH` / `WXPAY_NOTIFY_URL`。
3. **切换通道**：`PAY_CHANNEL=wxpay`（默认 `mock`）。
   注意 `WxPayNotifyController` 与 SDK Bean 均为条件装配，
   通道为 mock 时不会暴露回调接口。
4. **真机验证**：多卡展示与选择、唤起收银台、支付后余额到账、
   重复回调不重复入账。
5. **后台确认**：储值套餐页可增删改金额、赠券、使用说明，
   且「使用说明」现在有真实落库位置（`usage_paragraphs`）。

### 已知约束

- 充值「份数」通过**多次下单**实现（每次一张卡），
  未在后端引入「份数」概念，避免对账时单号与金额语义复杂化。
- 退款链路本次**未实现**（`stored_value_order` 已存 `payer_openid`，
  具备原路退回的前置数据）。如需退款需另立任务。
