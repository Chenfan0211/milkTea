# 提现对接「微信商家转账到零钱」实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有「提现同步标记 PAID」的假出款，升级为对接微信「商家转账到零钱」（`transfer_batches`）的真实打款，并保留 mock 通道以便未配置商户号前不破坏线上。

**Architecture:** 出款逻辑落在 `trade-service`（已有微信支付 SDK/证书/回调解析，可复用同一套商户凭证）。`server`（finance 域的 `WithdrawalService`）不再直接记账 PAID，而是通过内部接口委托 `trade-service` 发起转账，并根据回调/查询结果收敛提现状态。新增 `PayoutGateway` 抽象（对齐 `PaymentGateway` 模式），mock 与 wxpay 双实现 + `app.pay.channel` 开关切换。

**Tech Stack:** Spring Boot 3.3.5 / wechatpay-java 0.2.14 / MyBatis-Plus / MySQL；server 与 trade-service 走 `lb://` 内部调用。

**Spec:** 用户需求（本会话）：提现后续要对接「微信提现到零钱通」，选型「商家转账到零钱」，出款逻辑放 trade-service，用开关切换（mock 维持现状 / wxpay 走真实转账）。

---

## 一、现状与关键事实（已核实）

| 项 | 现状 |
|----|------|
| 提现闭环 | `server/.../WithdrawalService`：申请冻结 → 审核 → `settlePaid` 记账 + 置 PAID → 失败/驳回解冻 |
| 假出款点 | `settlePaid()` 只写台账快照（`WITHDRAW` 负向扣冻结）+ 置 `status=PAID`，**无任何真实打款** |
| 表结构 | `withdrawal` 已有 `callback_time`、`pay_time`、`failure_reason`；**缺**：微信转账单号、转账批次号、转账状态码 |
| openid | `app_user.open_id` 已存在；`user-service` 提供 `/internal/users/{id}/openid`（已用于支付） |
| 微信 SDK | `trade-service` 已有 `wechatpay-java 0.2.14`，`WxPaySdkConfig` 提供 `Config`/`NotificationParser`，`app.pay.channel=mock|wxpay` 条件装配 |
| 支付网关抽象 | `PaymentGateway` + mock/wxpay 双实现 + `PaymentGatewayResolver`（可对齐新增 PayoutGateway） |
| 内部调用 | `trade-service` 的 `RemoteUserQueryAdapter` 已示范 `lb://` 调 user-service 内部接口 |

## 二、微信「商家转账到零钱」接口要点

- 接口：`POST https://api.mch.weixin.qq.com/v3/transfer/batches`（商家转账批次单）
- 请求关键字段：`out_batch_no`（商户批次号）、`batch_name`、`total_amount`（分）、`total_num`、`transfer_detail_list[]`（含 `out_detail_no` 商户单号、`transfer_amount` 分、`openid` 收款人、`user_name` 选填）
- 结果：**异步**。返回 `batch_id`（微信批次号），最终到账结果由**转账结果通知回调**（`/v3/transfer/batches/...` 回调）或主动查询 `GET /v3/transfer/batches/batch-id/{batch_id}` / `.../out-batch-no/{out_batch_no}` 确认
- 单笔限额：单商户给单个微信用户单日转账上限（按微信规则，一般为 2000 元/日/用户，具体以商户号签约额度为准）
- 前提：商户号开通「商家转账」产品权限 + 配置回调地址 + 具备证书

## 三、状态机设计（关键，资金安全核心）

现有状态：`APPLIED / AUDITING / APPROVED / PAID / REJECTED / FAILED`

新增/细化：

```
APPLIED  --审核通过--> APPROVED --发起转账--> PROCESSING --回调成功--> PAID
                                          |--回调失败/查询失败--> FAILED(自动解冻)
                                          |--转账受理失败--> FAILED(解冻)
```

- 新增 `PROCESSING`（转账已受理、结果待定），**不再在 `settlePaid` 里同步置 PAID**
- `PAID` 只在收到微信「转账成功」回调（或主动查询确认 SUCCESS）后才置
- 幂等关键：`out_detail_no` 用 `withdraw_no`，回调/查询按它去重，避免重复打款

## 四、任务总览（严格按序）

1. Task 1：`withdrawal` 表新增转账字段（迁移）
2. Task 2：`trade-service` 新增 `PayoutGateway` 抽象 + mock/wxpay 实现 + 转账回调
3. Task 3：`trade-service` 新增内部出款接口 + 回调处理
4. Task 4：`server` `WithdrawalService` 改造为「委托出款 + 回调/查询收敛」
5. Task 5：配置项与开关（mock 默认，wxpay 需证书）
6. Task 6：单测 + 端到端验证
7. Task 7：部署与回滚

---

## Global Constraints

- **资金幂等**：`out_detail_no` 必须用 `withdraw_no`（唯一键），回调与主动查询都按它去重；重复回调不得二次扣账或二次置 PAID。
- **fail-closed**：转账发起失败、回调超时、查询失败，一律走 `FAILED + 解冻`，**不得**静默置 PAID（现在的同步标记正是要消除的隐患）。
- **金额单位**：全程「分」，对外展示才转元。
- **openid 必须服务端查**：绝不接受前端传 openid（与支付一致），由 `trade-service` 调 `user-service` `/internal/users/{id}/openid`。
- **开关隔离**：`app.pay.channel=mock` 时（当前线上），出款维持现有记账行为，**零请求微信**；切 `wxpay` 才装配真实转账 Bean（对齐 `WxPaySdkConfig` 的 `@ConditionalOnProperty`）。
- **不破坏现有提现链路**：mock 通道下，小程序提现、后台审核、失败解冻行为与现状完全一致。

---

## Task 1：`withdrawal` 表新增转账字段

**Files:**
- Add: `server/src/main/resources/db/migration/V69__withdrawal_payout_fields.sql`

- [ ] Step 1：新增字段（幂等，用 information_schema 判存在）

```sql
ALTER TABLE withdrawal
  ADD COLUMN transfer_batch_no VARCHAR(64) NULL COMMENT '微信转账批次号(batch_id)',
  ADD COLUMN transfer_status   VARCHAR(32) NULL COMMENT '微信转账状态(SUCCESS/FAILED/PROCESSING等)',
  ADD COLUMN transfer_fail_msg VARCHAR(255) NULL COMMENT '微信转账失败原因';
```

- [ ] Step 2：同步更新 `Withdrawal` 实体 + `V62` 注释迁移（可选）

## Task 2：`trade-service` 新增 `PayoutGateway` 抽象

**Files:**
- Add: `trade-service/src/main/java/com/wuling/trade/pay/PayoutGateway.java`
- Add: `.../pay/payout/MockPayoutGateway.java`
- Add: `.../pay/payout/WxPayoutGateway.java`
- Add: `.../pay/payout/WxPayoutResult.java`（受理结果）

- [ ] Step 1：定义接口（对齐 `PaymentGateway`）

```java
public interface PayoutGateway {
    String channel();
    /** 发起转账批次（单笔）；返回受理结果；通道不支持返回 null */
    WxPayoutResult transfer(String outDetailNo, long amountFen, String openid, String remark);
    /** 查询转账结果（补偿回调丢失）；查不到返回 null */
    PayoutQueryResult query(String outDetailNo);
}
```

- [ ] Step 2：mock 实现 —— 立即返回「成功」，与现有 `settlePaid` 记账行为一致（未配置商户号时走这条）

- [ ] Step 3：wxpay 实现 —— 调 `transfer_batches`，返回 `batch_id`；查询用 `out-batch-no` 接口

## Task 3：`trade-service` 内部出款接口 + 回调

**Files:**
- Add: `trade-service/.../pay/payout/PayoutController.java`（内部接口）
- Add: `trade-service/.../pay/payout/PayoutNotifyController.java`（微信回调）

- [ ] Step 1：内部接口 `POST /internal/payout/apply`，入参 `{ withdrawNo, amountFen, userId, subjectId, roleType }`，服务端按 userId 查 openid 后调 gateway
- [ ] Step 2：回调接口 `POST /api/v1/payout/notify`（仅回环/微信可调，验签 + AES 解密），解析批次结果后回调 server 收敛状态

## Task 4：`server` `WithdrawalService` 改造

**Files:**
- Modify: `server/.../finance/service/WithdrawalService.java`
- Modify: `server/.../finance/controller/WithdrawalController.java`

- [ ] Step 1：`settlePaid` 拆分为「发起转账 → 置 PROCESSING」；新增 `confirmPaid`（回调成功）与 `confirmFailed`（回调失败/查询失败 → 解冻）
- [ ] Step 2：新增端口 `PayoutPort`（server → trade-service `/internal/payout/apply`），server 不再本地记账 PAID，而是委托后置 PROCESSING
- [ ] Step 3：审核通过后：调 `PayoutPort.transfer(...)`，受理成功置 `PROCESSING + transfer_batch_no`，受理失败置 `FAILED + 解冻`

## Task 5：配置与开关

- [ ] `app.pay.channel=mock`（默认，当前线上保持）→ 出款走 mock，维持现状
- [ ] `app.pay.channel=wxpay` → 装配 `WxPayoutGateway`，需 `WXPAY_*` 证书/密钥 + 转账回调地址

## Task 6：测试

- [ ] `WithdrawalServiceTest` 补：审核通过后置 PROCESSING（非 PAID）、mock 通道成功收敛 PAID、失败解冻
- [ ] `WxPayoutGatewayTest` 补：转账受理、查询、幂等
- [ ] 端到端：mock 通道下小程序提现 → 后台审核 → 记录状态流转正确

## Task 7：部署与回滚

- [ ] mock 通道（默认）：**无需证书**，直接部署即可，行为与现状一致
- [ ] 切 wxpay 前：商户号开通「商家转账」权限 + 证书落盘 + 回调地址备案 HTTPS
- [ ] 回滚：切回 `app.pay.channel=mock` 即可，无需回滚代码

---

## 五、风险与待确认

| 优先级 | 项 |
|--------|----|
| **P0** | 单用户单日转账限额（2000 元/日，以签约额度为准）需在提现规则里提示，超限如何处理（拆批？拒单？）需产品确认 |
| P0 | 「商家转账到零钱」需要商户号单独开通该产品权限（与 JSAPI 收单权限不同），需确认现有商户号是否已开通 |
| P1 | 提现手续费：微信商家转账是否有手续费、是否从提现金额扣（现有 `fee` 字段恒 0） |
| P1 | 回调地址需 HTTPS 备案域名；当前项目 HTTPS 尚未启用（见 `docs/生产部署记录.md` P1 待办），**接入真实转账前必须先解决 HTTPS** |
| P2 | 大额提现（> 即时额度）目前走人工审核，审核后是「单笔转账」还是「拆多笔」需定义 |
| P2 | `transfer_batches` 单笔上限 2000 元（默认），超限需拆 `transfer_detail_list` 多笔或分次 |

---

## 六、验收标准

- [ ] mock 通道：提现申请→审核→状态流转与现状一致（不回归）
- [ ] wxpay 通道（有商户号后）：申请→审核→真实转账→回调置 PAID；失败/超时自动解冻
- [ ] 幂等：重复回调/重复查询不重复打款、不重复解冻
- [ ] openid 由服务端查询，前端无传入路径
- [ ] 开关切换无需改代码
