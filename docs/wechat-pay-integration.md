# 微信支付接入方案（订单支付）

> 文档性质：**方案设计 + 实施记录**。
> 范围：仅「订单支付」场景（点单下单后支付）。
> 不在本次范围：储值充值、礼品卡购买、退款、对账、提现（见第 9 节「后续扩展」）。
> 编写日期：2026-09-22
>
> **实施状态（2026-09-22 更新）**：后端代码**已全部写完**，默认通道保持 `mock`，
> 域名备案通过前不会向微信发起任何请求。备案完成后仅需切换配置即可启用，
> 详见第 12 节「实施记录」与第 13 节「备案后的启用步骤」。

---

## 1. 目标与现状

### 1.1 目标

把订单支付从 Mock 通道切换为微信支付（小程序 JSAPI）。用户在小程序内下单后唤起微信收银台，支付成功后订单进入已支付状态并生成取餐码。

### 1.2 现有基础（可直接复用）

项目已为支付接入预留了完整骨架，本次属于「填实现」而非「造轮子」：

| 组件 | 位置 | 现状 |
| --- | --- | --- |
| `PaymentGateway` | `server/.../trade/service/` | 支付适配接口，注释明确「接入真实通道时新增实现类即可」 |
| `MockPaymentGateway` | 同上 | Mock 实现，当前生效 |
| `PaymentService` | 同上 | 支付单创建、回调处理、幂等（行锁 + 状态机）已完备 |
| `PaymentCallbackSigner` | `wuling-common/.../common/security/` | 回调验签，已有 |
| `PaymentSignConfig` | `server/.../common/config/` | 验签配置，**fail-fast**：未注入密钥则拒绝启动 |
| `AppOrderController` | `server/.../trade/controller/` | 已有 `/orders/{orderNo}/pay` 与 `/payments/callback` |
| `Payment` 实体 | `server/.../trade/entity/` | 含 `thirdStatus`、`standardStatus`、`transactionId`、`callbackTime` |
| 小程序端 | `user-h5/pages/order-confirm` | 已按微信支付分支预留（当前走 Mock） |

### 1.3 关键差异

现有回调验签是 **HMAC + 共享密钥**（`PaymentCallbackSigner`），用于「自家网关回调」场景。

微信支付 APIv3 使用 **平台证书 / 微信支付公钥 + RSA-SHA256 验签**，机制不同，需新增独立验签实现，**不能复用**现有 `PaymentCallbackSigner`（该组件保留给内部网关回调）。

---

## 2. 商户资质准备（前置）

接入前需在 [微信支付商户平台](https://pay.weixin.qq.com/) 完成：

| 项 | 说明 |
| --- | --- |
| 商户号 `mchId` | 已有（用户确认） |
| 小程序 AppID 绑定 | 商户号需与小程序 AppID 完成绑定 |
| APIv3 密钥 | 商户平台设置，用于请求体敏感字段解密与回调资源解密 |
| 商户 API 证书 | `apiclient_cert.pem` + `apiclient_key.pem`，用于请求签名 |
| 微信支付公钥 / 平台证书 | 用于回调验签（新商户建议用「微信支付公钥」模式） |
| 支付授权目录 | 小程序无需配置，但需确认 AppID 与 mchId 关联正确 |

> 证书与密钥**一律通过部署环境注入**，不得写入仓库（参考本项目已有约定：`PAY_CALLBACK_SECRET` 的处理方式）。

---

## 3. 架构设计

### 3.1 分层

```
小程序                      后端 (server)                    微信支付
  │                              │                              │
  │ ① POST /orders/{no}/pay      │                              │
  ├─────────────────────────────►│                              │
  │   body: { channel: 'WXPAY' } │                              │
  │                              │ ② 创建/复用支付单             │
  │                              │    (Payment.status=PAYING)   │
  │                              │ ③ 调统一下单 JSAPI            │
  │                              ├─────────────────────────────►│
  │                              │◄─────────────────────────────┤
  │                              │    返回 prepay_id            │
  │ ④ 返回支付参数                │ ⑤ 组装小程序支付参数          │
  │◄─────────────────────────────┤    (二次签名)                │
  │                              │                              │
  │ ⑥ wx.requestPayment(...)     │                              │
  ├─────────────────────────────────────────────────────────────►│
  │                              │                              │
  │                              │ ⑦ 支付结果通知（回调）        │
  │                              │◄─────────────────────────────┤
  │                              │ ⑧ 验签 → 解密 → 校验金额      │
  │                              │ ⑨ 置订单已支付 + 生成取餐码    │
  │                              │ ⑩ 应答 SUCCESS                │
  │                              ├─────────────────────────────►│
  │                              │                              │
  │ ⑪ 轮询/查询订单状态           │                              │
  ├─────────────────────────────►│                              │
```

**关键点**：

- **支付结果以后端回调为准**，不依赖小程序端 `requestPayment` 的 `success` 回调（该回调不代表资金到账）。
- 小程序端在 `requestPayment` 成功/失败后，**主动查询一次订单状态**，以服务端结果为准。

### 3.2 通道选择

引入 `WechatPayGateway implements PaymentGateway`，与 `MockPaymentGateway` 共存，通过配置切换：

```yaml
app:
  pay:
    channel: ${PAY_CHANNEL:mock}   # mock | wxpay
```

由 `PaymentService` 按配置选择实现，避免改动业务逻辑。这也保证本地开发与 CI 无需真实商户号即可运行。

---

## 4. 接口设计

### 4.1 发起支付（改造现有）

```
POST /api/v1/app/orders/{orderNo}/pay
```

**请求**（保持现有 `PayRequest`，扩展 channel 取值）：

```json
{
  "channel": "WXPAY",
  "amount": 1890,
  "idempotentKey": "mini-<orderNo>"
}
```

**响应**（`WXPAY` 通道返回小程序支付参数）：

```json
{
  "code": 0,
  "data": {
    "orderNo": "D00235803499139801085",
    "paymentNo": "4200001234202609221234567890",
    "payParams": {
      "timeStamp": "1758523456",
      "nonceStr": "abc123...",
      "package": "prepay_id=wx22000000000000000000000000",
      "signType": "RSA",
      "paySign": "..."
    }
  }
}
```

**约定**：

- `amount` 单位为**分**（与项目现有约定一致，见 `server/README.md`：金额 `BIGINT` 分）。
- `amount` 必须与订单 `paidAmount` 完全一致，服务端二次校验（现有逻辑已实现）。
- `MOCK` 通道响应保持现状，便于本地联调。

### 4.2 支付结果通知（新增，独立于现有回调）

```
POST /api/v1/app/payments/wxpay/notify
```

**为什么新增而非复用 `/payments/callback`**：

现有 `/payments/callback` 使用 HMAC 共享密钥验签，面向内部网关；微信回调使用 RSA 平台证书验签、AES-GCM 解密资源，且需按微信规范应答（成功返回 `{"code":"SUCCESS"}`，失败返回 `{"code":"FAIL","message":"..."}`）。两者协议不兼容，混用会导致验签失败与应答格式错误。

**处理流程**（严格按序）：

1. 读取请求头 `Wechatpay-Signature`、`Wechatpay-Timestamp`、`Wechatpay-Nonce`、`Wechatpay-Serial`
2. **校验时间戳**（与当前时间偏差 > 5 分钟直接拒绝，防重放）
3. **RSA 验签**（用微信支付公钥/平台证书，按 `Wechatpay-Serial` 选取）
4. **AES-GCM 解密** `resource` 字段（用 APIv3 密钥）
5. **校验业务字段**：`mchid`、`appid` 与自身配置一致；`out_trade_no` 存在；`amount.total` 与订单金额一致；`trade_state` 为 `SUCCESS`
6. **幂等处理**：调用现有 `PaymentService.handleCallback(orderNo, transactionId)`
7. 应答 `{"code":"SUCCESS"}`

**失败一律应答 `{"code":"FAIL","message":"..."}`**，微信会按策略重试。

### 4.3 订单状态查询（复用现有）

```
GET /api/v1/app/orders/{orderNo}
```

小程序端在 `requestPayment` 回调后调用，以服务端状态为准。

---

## 5. 数据模型

### 5.1 现有表（无需改动）

`payment` 表已有字段足够：`payment_no`、`order_no`、`amount`、`channel`、`third_status`、`standard_status`、`transaction_id`、`callback_time`。

### 5.2 需要补充的字段

| 表 | 字段 | 说明 |
| --- | --- | --- |
| `payment` | `prepay_id` | 微信预支付会话标识，便于排查与对账 |
| `payment` | `payer_openid` | 支付用户 openid，退款与对账需要 |

新增 migration：`V14__payment_wxpay_fields.sql`（版本号需按当时最新顺延）。

### 5.3 状态映射

| 微信 `trade_state` | 项目 `standard_status` |
| --- | --- |
| `SUCCESS` | `PAID` |
| `NOTPAY` | `PAYING` |
| `CLOSED` | `CLOSED` |
| `REFUND` | `REFUNDED` |
| `PAYERROR` | `FAILED` |

`third_status` 保留微信原始值，便于追溯。

---

## 6. 安全要求

| 项 | 要求 |
| --- | --- |
| **密钥存储** | APIv3 密钥、商户私钥、微信支付公钥均由部署环境注入（环境变量或挂载文件），**不得入库、不得入仓库** |
| **回调验签** | 必须验签 + 解密 + 金额比对，任一步失败即拒绝 |
| **防重放** | 校验 `Wechatpay-Timestamp` 偏差（±5 分钟） |
| **幂等** | 复用现有「订单行锁 + 状态机」；重复回调直接返回成功，不重复入账 |
| **日志脱敏** | 密钥、证书内容、完整用户标识不得进日志；`openid` 输出需截断 |
| **金额校验** | 回调金额必须与订单 `paidAmount` 一致，不一致拒绝并告警 |
| **证书轮换** | 微信平台证书会定期更换，需支持按 `Wechatpay-Serial` 动态选取；建议接入 `GET /v3/certificates` 定时刷新 |
| **启动校验** | 参考 `PaymentSignConfig` 的 fail-fast：`PAY_CHANNEL=wxpay` 时若证书/密钥缺失，服务应拒绝启动 |

---

## 7. 改动清单（预估）

### 7.1 后端

| 文件 | 操作 | 说明 |
| --- | --- | --- |
| `trade/gateway/WechatPayGateway.java` | 新增 | 实现 `PaymentGateway` |
| `trade/gateway/WechatPayClient.java` | 新增 | 统一下单、订单查询、证书获取 |
| `trade/gateway/WechatPaySigner.java` | 新增 | 请求签名（RSA-SHA256） |
| `trade/gateway/WechatPayVerifier.java` | 新增 | 回调验签（平台证书） |
| `trade/gateway/WechatPayConfig.java` | 新增 | 商户号、证书、APIv3 密钥加载与 fail-fast |
| `trade/dto/WxPayParams.java` | 新增 | 小程序支付参数（timeStamp/nonceStr/package/signType/paySign） |
| `trade/controller/AppOrderController.java` | 改造 | `/pay` 按 channel 分支；新增 `/payments/wxpay/notify` |
| `trade/service/PaymentService.java` | 改造 | 注入 `PaymentGateway` 实现（按配置选择），而非直接依赖 Mock |
| `trade/service/PaymentGateway.java` | 扩展 | 增加 `prepayForMiniApp()` 返回支付参数（现有 `prepay` 返回 String，语义过窄） |
| `db/migration/V14__payment_wxpay_fields.sql` | 新增 | 补 `prepay_id`、`payer_openid` |
| `pom.xml`（server） | 改造 | 引入微信支付 SDK 或自实现 HTTP + 加解密 |

**SDK 选择**：

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| 官方 `wechatpay-java` | 签名/验签/加解密开箱即用，维护成本低 | 引入依赖 |
| 自实现（HTTP + BouncyCastle） | 无额外依赖，可控 | 签名细节多，易出错（尤其证书轮换与 AES-GCM） |

**建议用官方 SDK**，理由：支付签名细节复杂（证书序列号选取、RSA 填充、AES-GCM 附加数据），自实现出错的代价是资金问题。

### 7.2 小程序端

| 文件 | 操作 | 说明 |
| --- | --- | --- |
| `pages/order-confirm/order-confirm.js` | 改造 | `createOrderAndPay` 改为「下单 → 发起支付 → 唤起收银台 → 查询状态」 |
| `utils/api.js` | 改造 | `payOrder` 返回支付参数；新增订单状态查询 |
| `pages/order-detail/order-detail.js` | 改造 | 「立即支付」接入真实支付 |

**小程序端流程**：

```js
1. createOrder(...)                         → 拿到 orderNo
2. payOrder(orderNo, amount, 'WXPAY')       → 拿到 payParams
3. wx.requestPayment({ ...payParams, ... })
4. 无论成功失败，都查询订单状态（以服务端为准）
   - success 且服务端 PAID → 跳订单详情
   - fail（用户取消）→ 提示「支付已取消」，订单保持待支付
5. 订单列表/详情页对「待支付」展示倒计时与重新支付入口（现有逻辑保留）
```

### 7.3 配置

```yaml
app:
  pay:
    channel: ${PAY_CHANNEL:mock}              # mock | wxpay
    wxpay:
      mch-id: ${WXPAY_MCH_ID:}
      app-id: ${WXPAY_APP_ID:}
      api-v3-key: ${WXPAY_API_V3_KEY:}
      merchant-serial-no: ${WXPAY_MCH_SERIAL_NO:}
      private-key-path: ${WXPAY_PRIVATE_KEY_PATH:}
      public-key-path: ${WXPAY_PUBLIC_KEY_PATH:}
      public-key-id: ${WXPAY_PUBLIC_KEY_ID:}
      notify-url: ${WXPAY_NOTIFY_URL:}
```

---

## 8. 测试策略

| 层级 | 内容 |
| --- | --- |
| **单元测试** | 签名生成（固定输入 → 固定输出）、回调验签（正确/篡改/过期时间戳）、金额比对、状态映射 |
| **集成测试** | `/pay` 在 `channel=WXPAY` 下的参数组装；回调接口的幂等（重复通知只入账一次） |
| **沙箱/联调** | 微信支付无公开沙箱，需用**真实小额订单**验证；建议金额 0.01 元 |
| **异常路径** | 用户取消支付、支付超时（订单被 MQ 关闭）、回调重复、金额篡改、证书过期 |
| **回归** | `MOCK` 通道需保持可用，确保本地开发与 CI 不受影响 |

**必须覆盖的边界**：

- 同一订单并发支付请求
- 支付成功但回调未到达（用户已付款）→ 主动查询补偿
- 回调到达但订单已被超时关闭 → 需触发退款（**本次先记录告警，退款在后续批次**）

---

## 9. 后续扩展（本次不做）

| 场景 | 说明 |
| --- | --- |
| 储值充值 | `StoredValueOrder` 支付 |
| 礼品卡购买 | `GiftCardOrder` 支付 |
| 退款 | `RefundService` 对接微信退款 API，含退款回调 |
| 对账 | 下载微信账单，与 `payment` / `fund_flow` 核对 |
| 提现 | 商家转账到零钱（需单独开通） |
| 分账 | 微信分账 API（当前项目分账为内部记账，需评估是否改为微信分账） |

---

## 10. 实施步骤建议

分阶段推进，每阶段可独立验证：

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **P1** | 抽象 `PaymentGateway` 选型（配置驱动），`MOCK` 通道回归通过 | 现有功能不受影响 |
| **P2** | 实现 `WechatPayConfig` + `WechatPaySigner`，补齐单元测试 | 签名、验签单测通过 |
| **P3** | 实现统一下单 + 小程序支付参数组装，联调唤起收银台 | 真机可唤起，小额实付成功 |
| **P4** | 实现回调验签与解密，打通「支付 → 订单已支付 → 取餐码」 | 回调可正确入账，重复回调幂等 |
| **P5** | 小程序端完整流程（含取消、轮询、超时） | 全链路走通 |
| **P6** | 安全加固：日志脱敏、证书轮换、异常告警 | 安全清单逐项确认 |

---

## 11. 风险与注意事项

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| **证书轮换** | 微信平台证书定期更换，硬编码会失效 | 按 `Wechatpay-Serial` 动态选取 + 定时刷新 |
| **回调丢失** | 网络异常可能导致回调未达 | 主动查询补偿 + 定时对账 |
| **金额单位** | 微信以「分」为单位，项目也是「分」，需注意小程序端展示的「元」转换 | 前后端约定：接口层一律「分」 |
| **重复支付** | 用户多次点击支付 | 复用现有行锁 + 状态机 + `idempotentKey` |
| **超时订单已关闭** | 回调到达时订单已被 MQ 关闭 | 记录告警，后续批次接入自动退款 |
| **与微服务改造并行** | 项目正在拆分模块，`trade` 域未来可能独立 | 本次在 `server` 内实现；若期间 `trade` 迁移，需同步迁移支付网关 |

---

## 附：与现有 Mock 通道的关系

本次**不移除** `MockPaymentGateway`：

- 本地开发与自动化测试继续使用 `MOCK`，无需真实商户号
- 生产通过 `PAY_CHANNEL=wxpay` 切换
- 两者实现同一接口，业务代码无感知

这样既满足上线需求，也保留了开发效率。

---

## 12. 实施记录（2026-09-22）

后端已按本方案落地，**默认通道为 `mock`，微信支付代码处于「已写好、未启用」状态**。

### 12.1 已实现的文件

| 文件 | 说明 |
| --- | --- |
| `trade/pay/wxpay/WxPayProperties.java` | 配置项 + 启动期 fail-fast 校验（仅 wxpay 通道生效） |
| `trade/pay/wxpay/WxPaySdkConfig.java` | SDK Bean 装配，`@ConditionalOnProperty` 按通道隔离 |
| `trade/pay/wxpay/WxPayNotifyService.java` | 回调验签 + AES-GCM 解密 + 时间戳防重放 + 商户号/AppID 比对 |
| `trade/pay/wxpay/WxPayNotifyController.java` | `POST /api/v1/app/payments/wxpay/notify`，按微信规范应答 |
| `trade/pay/wxpay/WxPayStatusMapper.java` | `trade_state` → 项目标准状态映射 |
| `trade/pay/wxpay/WxPayParams.java` 等 3 个 DTO | 小程序支付参数、下单结果、交易信息 |
| `trade/service/PaymentGatewayResolver.java` | 按配置选型，配置错误则拒绝启动 |
| `trade/service/WechatPayGateway.java` | `PaymentGateway` 的微信实现 |
| `trade/service/PaymentService.java` | 改为按选型取通道；新增小程序下单与微信回调入账（含**金额强校验**） |
| `trade/port/UserQueryPort.java` + `RemoteUserQueryAdapter.java` | 取 openid（服务端查询，不接受前端传入） |
| `user/controller/UserInternalController.java` | `GET /internal/users/{id}/openid` |
| `db/migration/V15__payment_wxpay_fields.sql` | `payment` 补 `prepay_id`、`payer_openid` |
| `gateway/.../GatewayAuthPolicy.java` | 白名单放行微信回调路径 |
| 各服务 `application*.yml` | `app.pay.*` 配置段（生产模板已同步） |

### 12.2 与原始方案的差异（已按实际实现修订）

| # | 原方案 | 实际实现 | 原因 |
| --- | --- | --- | --- |
| 1 | 安全加固放在最后（P6） | **回调金额校验、独立验签入口已随本次一起落地** | 原方案的安全项依赖备案，中间态会长期裸奔 |
| 2 | 未提应答验签 | SDK 的 `DefaultHttpClientBuilder` 已内置应答验签 | 微信 v3 应答带签名，缺失等于不校验微信身份 |
| 3 | 回调沿用 `handleCallback` | 新增 `handleWxPayCallback`，**强制校验 `amount.total` 与订单实付一致** | 原 `handleCallback` 不校验金额，属资损敞口 |
| 4 | 未提 openid 来源 | 新增 `/internal/users/{id}/openid` 内部接口 | 支付必需，且绝不能让前端传（否则可替他人下单支付） |
| 5 | 未提 API base | `api-base-url` 可配置 | 换成「本地假微信服务端」后，无商户号也能端到端验证 |
| 6 | 小程序端改造在 P5 | 顺延到备案后（第 13 节） | 提前改会让 mock 全链路失效 |

### 12.3 已验证内容（29 个单测，全部通过）

| 测试类 | 覆盖 |
| --- | --- |
| `WxPayStatusMapperTest` (6) | 状态映射；**未知状态必须返回 null，不得默认成功** |
| `WxPayPropertiesTest` (6) | mock 不校验；wxpay 缺配置/密钥非 32 位/回调非 https/证书不可读 均拒绝启动 |
| `WxPayNotifyServiceTest` (5) | 缺请求头、缺请求体、时间戳超窗口、时间戳格式非法 |
| `PaymentGatewayResolverTest` (7) | 默认走 mock；显式 wxpay；配置不存在通道拒绝启动；重复通道拒绝启动 |
| `PaymentChannelWiringTest` (4) | **mock 通道下微信支付 Bean 全部不存在**（不读证书、不请求微信） |

同时全量回归通过：`mvn test` 20 个测试类全绿，`user-h5` 的 `npm run check` 全绿。

### 12.4 当前状态对生产的影响

- 生产配置保持 `PAY_CHANNEL=mock`（默认值），行为与接入前**完全一致**；
- 微信支付相关 Bean 在 mock 通道下不会被创建，因此**不读取任何证书**、
  **不向 `api.mch.weixin.qq.com` 发起任何请求**（已由 `PaymentChannelWiringTest` 锁定）；
- `/api/v1/app/payments/wxpay/notify` 接口在 mock 通道下**不存在**，
  不会对外暴露一个无验签能力的公网入口。

---

## 13. 备案通过后的启用步骤

### 13.1 前置检查（微信侧）

1. 商户号已申请，且与小程序 AppID 完成绑定；
2. 已开通「JSAPI 支付」；
3. 已设置 APIv3 密钥（32 位）；
4. 已申请商户 API 证书，得到 `apiclient_key.pem` 与证书序列号；
5. 已获取微信支付公钥（`pub_key.pem` + 公钥 ID）。

### 13.2 服务器侧准备

1. 证书落盘到 `/opt/wuling/app/certs/`，权限 `600`，属主与 systemd 一致；
2. 在 `secrets.env` 追加：

```bash
PAY_CHANNEL=wxpay
WXPAY_MCH_ID=
WXPAY_APP_ID=
WXPAY_API_V3_KEY=
WXPAY_MCH_SERIAL_NO=
WXPAY_PRIVATE_KEY_PATH=/opt/wuling/app/certs/apiclient_key.pem
WXPAY_VERIFY_MODE=public-key
WXPAY_PUBLIC_KEY_ID=
WXPAY_PUBLIC_KEY_PATH=/opt/wuling/app/certs/pub_key.pem
WXPAY_NOTIFY_URL=https://api.wulingshiguang.top/api/v1/app/payments/wxpay/notify
```

3. 重启 trade-service，观察日志出现
   `支付通道已选定 channel=WXPAY` 与 `微信支付通道已启用`。

> 预期失败方式：若漏配任一项，服务**拒绝启动**并明确指出缺失的环境变量名 ——
> 这是刻意设计，避免带着无签名能力的通道上线。

### 13.3 域名与 HTTPS

1. 确认备案状态为「已备案」，`curl http://wulingshiguang.top/` 通；
2. 申请证书并启用 443，80 强制跳 443；
3. 微信公众平台配置 `request 合法域名` 为 `https://api.wulingshiguang.top`；
4. 删除预览配置 `/etc/nginx/conf.d/wuling-preview.conf`（可选）。

### 13.4 小程序端改造（此时才做）

| 文件 | 改动 |
| --- | --- |
| `utils/api.js` | `payOrder` 返回 `payParams` 而非订单 DTO |
| `utils/pay.js` | **新增**：统一封装 `wx.requestPayment` + 取消处理 + 状态轮询 |
| `pages/order-confirm/order-confirm.js` | 下单 → 支付 → 切换为「查服务端订单状态」 |
| `pages/order-detail/order-detail.js` | 「立即支付」接同一封装 |
| `config.js` | `BASE_URL: 'https://api.wulingshiguang.top'`、`USE_MOCK: false` |

**流程要点**：以服务端回调结果为准，`requestPayment` 的 `success` 不代表资金到账；
失败/取消后应主动查询一次订单状态。

### 13.5 联调验收清单

| 项 | 期望 |
| --- | --- |
| 0.01 元真实小额订单 | 可唤起收银台，支付成功后订单变 PAID 并生成取餐码 |
| 用户取消支付 | 提示「支付已取消」，订单保持待支付 |
| 重复回调 | 只入账一次（幂等） |
| 篡改金额的回调 | 被拒绝，且告警落盘 |
| 订单超时关闭后回调到达 | 拒绝入账 + CRITICAL 告警（人工核查退款） |
| `PAY_CHANNEL=mock` 回归 | 功能与当前一致（可随时回滚） |

### 13.6 回滚方式

把 `secrets.env` 的 `PAY_CHANNEL` 改回 `mock` 并重启 trade-service 即可。
小程序端若需回滚，同步把 `config.js` 的 `USE_MOCK` 改回 `true`。
